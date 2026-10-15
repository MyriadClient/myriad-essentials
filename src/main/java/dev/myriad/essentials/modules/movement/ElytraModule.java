package dev.myriad.essentials.modules.movement;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.InteractEvent;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Category;
import dev.myriad.api.module.Module;
import dev.myriad.api.service.Rotations;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.essentials.util.ChestSwap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * What the elytra flight modules ({@link Recast}, {@link Altitude}) share: flying with a rotation of their own that only
 * goes to the server and the flight physics (the camera stays yours), opening the elytra the way vanilla does, their own
 * <b>No Durability</b> (see {@link NoDurability}, which also catches a glide dropped in mid-air), and <b>Preserve</b>:
 * elytras worn down to it are put aside, the freshest other one going on (in mid-air too). One flies at a time; while
 * one is on, Auto Armor's No Durability Elytra stands down for its swaps.
 */
public abstract class ElytraModule extends Module implements NoDurability.Owner {
	private final BoolSetting noDurability = sgGeneral.bool("No Durability")
		.description("Keep the elytra from wearing out while you glide, by swapping it with a chestplate from your inventory.")
		.defaultValue(true).build();
	private final DoubleSetting preserve = sgGeneral.doubleSetting("Preserve")
		.description("Put elytras with less durability than this (%) aside, and fly with another.").defaultValue(5).range(0, 50).decimals(0).build();

	/** Flying with {@link #spoof}'s rotation this tick. */
	boolean spoofing;
	/** Open the elytra this tick (see {@link #startGliding}); and whether jump was pressed last tick. */
	boolean wantOpen, jumpedLastTick;
	/** Keep gliding client-side through ground touches (Recast's bounce). */
	boolean holdGlide;
	/** The server set you back since the last tick. */
	volatile boolean flagged;
	private boolean preserveWarned;
	final NoDurability noDur = new NoDurability(this);

	protected ElytraModule(Category category, String name, String description) {
		super(category, name, description);
	}

	@Override
	protected void onEnable() {
		spoofing = holdGlide = flagged = wantOpen = false;
		resetFlight();
		noDur.enable();
		Myriad.inventory().spare(this, this::wornDown);
	}

	@Override
	protected void onDisable() {
		resetFlight();
		spoofing = holdGlide = false;
		noDur.disable();
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
		return noDurability.get();
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

	/** No Durability's input, after everyone else's: jump only as the press that starts a glide again. */
	@Subscribe(priority = Priority.LOWEST)
	private void onInputLast(InputEvent e) {
		noDur.onInput(e);
	}

	@Subscribe
	private void onUse(InteractEvent.Item e) {
		noDur.onUse(e);
	}

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onSetBack(PacketEvent.Receive e) {
		flagged = true;
		noDur.setBack();
	}

	// ---- Preserve -------------------------------------------------------------------------------------------------

	/** An elytra worn down to Preserve: put aside, to be mended. */
	boolean wornDown(ItemStack s) {
		return ItemInfo.isGlider(s) && s.isDamageableItem() && ItemInfo.durabilityFraction(s) * 100 < preserve.get();
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
		return ItemInfo.canGlide(p.getItemBySlot(EquipmentSlot.CHEST)) || (noDurability() && ChestSwap.canGlide());
	}

	/** Disconnects (turning off first, so it doesn't carry on when you join again). Not in singleplayer. */
	void leave(String why) {
		disable();
		if (mc.isLocalServer() || mc.getConnection() == null) {
			warn(why + ": turning off");
			return;
		}
		mc.getConnection().getConnection().disconnect(Component.literal("[" + name() + "] " + why));
	}
}
