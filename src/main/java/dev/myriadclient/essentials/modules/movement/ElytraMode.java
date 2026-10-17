package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.ItemInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * What {@link ElytraFly}'s types ({@link Recast}, {@link Altitude}, {@link ElytraControl}) share: flying with a rotation
 * of their own that only goes to the server and the flight physics (the camera stays yours), opening the elytra the
 * way vanilla does, <b>No Durability</b> (core's {@link NoDurability}, which also catches a glide dropped in mid-air), and
 * <b>Preserve</b>: elytras worn down to it are put aside, the freshest other one going on (in mid-air too). The type
 * flying hears events only while it flies ({@link #start}, {@link #stop}); its settings are Elytra Fly's, shown only
 * while it's the one chosen. While one flies, Auto Armor's No Durability Elytra stands down for its swaps.
 */
public abstract class ElytraMode implements NoDurability.Owner, Elytra.Pilot {
	protected static final Minecraft mc = Minecraft.getInstance();
	protected final ElytraFly fly;
	private final String name;
	/** Elytra Fly's General group: the type's settings go there, shown while it's chosen (see {@link #chosen}). */
	protected final SettingGroup sgGeneral;

	/** Flying with {@link #spoof}'s rotation this tick. */
	boolean spoofing;
	/** Open the elytra this tick (see {@link #startGliding}); and whether jump was pressed last tick. */
	boolean wantOpen, jumpedLastTick;
	/** Keep gliding client-side through ground touches (Recast's bounce). */
	boolean holdGlide;
	/** The server set you back since the last tick. */
	volatile boolean flagged;
	private boolean preserveWarned;
	final NoDurability noDur = Myriad.elytra().noDurability(this);

	protected ElytraMode(ElytraFly fly, String name) {
		this.fly = fly;
		this.name = name;
		this.sgGeneral = fly.settings.group("General");
	}

	/** The type's name, as Elytra Fly's Type shows it. */
	public String name() {
		return name;
	}

	/** Whether this is the type chosen in Elytra Fly (its settings show then). */
	protected boolean chosen() {
		return fly.typeName().equals(name);
	}

	/** Starts flying: hears events from now on. */
	final void start() {
		onEnable();
		Myriad.events().subscribe(this);
	}

	/** Stops flying. */
	final void stop() {
		Myriad.events().unsubscribe(this);
		onDisable();
	}

	/** What Elytra Fly shows beside its name while this flies. */
	public String hudInfo() {
		return null;
	}

	protected static boolean inGame() {
		return mc.level != null && mc.player != null;
	}

	@Override
	public void info(String message) {
		fly.info(message);
	}

	@Override
	public void warn(String message) {
		fly.warn(message);
	}

	/** Turns Elytra Fly off. */
	protected void disable() {
		fly.disable();
	}

	protected void onEnable() {
		spoofing = holdGlide = flagged = wantOpen = false;
		resetFlight();
		Myriad.elytra().addPilot(this);
		noDur.enable();
		Myriad.inventory().spare(this, this::wornDown);
	}

	protected void onDisable() {
		resetFlight();
		spoofing = holdGlide = false;
		noDur.disable();
		Myriad.elytra().removePilot(this);
		Myriad.inventory().spare(this, null);
	}

	/** Back to the start: on enable and disable. */
	protected abstract void resetFlight();

	/** This tick's flight, before any of its actions. */
	protected abstract void tickFlight();

	/** Whether the module swaps the chest slot itself (Recast's bounce with No Durability): Preserve leaves it alone then. */
	protected boolean swapsChest() {
		return false;
	}

	/** This module's input, before the elytra-opening press is worked in. */
	protected void input(InputEvent e) {
	}

	/** After the elytra-opening press: what jump ended up as is known. */
	protected void inputDone(InputEvent e) {
	}

	@Override
	public boolean noDurability() {
		return fly.noDurability();
	}

	/** Flying with {@link #spoof}'s rotation: the glide, walking and jumps follow it (see Elytra.Pilot). */
	@Override
	public boolean steers() {
		return spoofing;
	}

	@Override
	public boolean holdsGlide() {
		return holdGlide;
	}

	/**
	 * Whether No Durability swaps a chestplate on for its breaks: on servers whose anti-cheat simulates the glide
	 * (Grim, 2b2t), the only way that passes there. Elsewhere it stops and starts the glide instead (see
	 * {@link NoDurability}), and a chestplate worn mid-glide would only be checked as walking.
	 */
	protected boolean chestSwaps() {
		return noDurability() && AntiCheat.strict();
	}

	// ---- tick -----------------------------------------------------------------------------------------------------

	/**
	 * Before any of this tick's actions (a restart sends held ping answers, which must come first: Grim's Post), and
	 * before Rocket Boost, so the flight rotation is asked for before it fixes this tick's rotation; No Durability runs at
	 * its end.
	 */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 10)
	private void onTick(TickEvent.Pre e) {
		wantOpen = false;
		if (!inGame()) {
			noDur.tick();
			return;
		}
		tickFlight();
		freshElytra();
		noDur.tick();
	}

	@Subscribe
	private void onInput(InputEvent e) {
		input(e);
		// Vanilla opens the elytra when jump goes from released to pressed in the air: release it for a tick if it's
		// held, then press it.
		if (wantOpen) e.jump = !jumpedLastTick;
		jumpedLastTick = e.jump;
		inputDone(e);
	}

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onSetBack(PacketEvent.Receive e) {
		flagged = true;
	}

	// ---- Preserve -------------------------------------------------------------------------------------------------

	/** An elytra worn down to Preserve: put aside, to be mended. */
	boolean wornDown(ItemStack s) {
		return ItemInfo.isGlider(s) && s.isDamageableItem() && ItemInfo.durabilityFraction(s) * 100 < fly.preserve();
	}

	/**
	 * Preserve: an elytra worn down to it comes off for the freshest other one, in mid-air too (one elytra for another
	 * keeps the glide), but not while No Durability or the bounce is swapping the chest slot: their swaps pick a fresh
	 * one themselves. Said once, on the ground, when there's none to swap in.
	 */
	private void freshElytra() {
		var p = mc.player;
		ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
		if (!wornDown(worn)) {
			preserveWarned = false;
			return;
		}
		if (noDur.swapping() || swapsChest() || p.isCreative()) return;
		int with = Myriad.inventory().bestInInventory(s -> ItemInfo.isGlider(s) && !ItemInfo.isBound(s) ? 1 + ItemInfo.durabilityFraction(s) : 0);
		if (with < 0) {
			if (!preserveWarned && p.onGround()) {
				warn("Elytra worn down to Preserve, and no other to fly with.");
				preserveWarned = true;
			}
			return;
		}
		if (Myriad.inventory().prepareClick()) Myriad.inventory().move(with, ItemInfo.inventoryIndex(EquipmentSlot.CHEST));
	}

	// ---- flight ---------------------------------------------------------------------------------------------------

	/** Flies (and tells the server) with this rotation, leaving the camera alone. */
	void spoof(float yaw, float pitch) {
		spoofing = true;
		Myriad.rotations().request(this, Mth.wrapDegrees(yaw), pitch, Rotations.PRIORITY_HIGH + 50);
	}

	/**
	 * Opens the elytra the way vanilla does: by pressing jump in the air after a tick with it released, so vanilla
	 * sends the start itself, before the tick's input and movement. Grim checks exactly that (a start with jump released
	 * the tick before and pressed in the same tick, and no two starts in a row); a start sent on its own is set back.
	 */
	void startGliding() {
		wantOpen = true;
	}

	/** Whether the elytra is really open (as the server last said, or as we just asked), ignoring {@link #holdGlide}. */
	boolean elytraOpen() {
		boolean held = holdGlide;
		holdGlide = false;
		boolean open = mc.player.isFallFlying();
		holdGlide = held;
		return open;
	}

	/** Whether the player could glide right now: an elytra on (or swappable on), and nothing vanilla forbids it for. */
	boolean canGlide() {
		var p = mc.player;
		boolean blocked = p.getAbilities().flying || p.isPassenger() || p.isInWater() || p.onClimbable() || p.hasEffect(MobEffects.LEVITATION);
		if (blocked) return false;
		// With No Durability a chestplate may be worn, with the elytra at hand to swap on. Not with an elytra one hit from
		// breaking: the server refuses a glide start with it.
		return ItemInfo.canGlide(p.getItemBySlot(EquipmentSlot.CHEST)) || (chestSwaps() && Myriad.elytra().chestSwap().canGlide());
	}

	/** Disconnects (turning Elytra Fly off first, so it doesn't carry on when you join again). Not in singleplayer. */
	void leave(String why) {
		disable();
		if (mc.isLocalServer() || mc.getConnection() == null) {
			warn(why + ": turning off");
			return;
		}
		mc.getConnection().getConnection().disconnect(Component.literal("[" + name() + "] " + why));
	}
}
