package dev.myriad.essentials.modules.movement;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.util.Packets;
import dev.myriad.essentials.util.ChestSwap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Flies the elytra like a creative flight: while you glide, the keys move you and nothing else does. Forward, back and
 * the sides go along the camera's heading at Speed, jump climbs and sneak descends at Vertical Speed, and with neither
 * you hold your height, sinking at Sink. Turning and slowing down are instant; speed builds up as fast as NoCheatPlus's
 * elytra check allows (it predicts a vanilla glide from your last move and your look, and takes up to 0.1 blocks a tick
 * more, whatever the direction), about 1.8 blocks a second each tick. The elytra still opens the vanilla way (jump in
 * the air). It doesn't fly into chunks that haven't loaded.
 * <p>
 * Made for servers that check elytra flight loosely (6b6t's NoCheatPlus, for one). One that simulates the glide, as
 * Grim does on 2b2t, sets this back from the first tick: use {@link Recast} or {@link Altitude} there. Applied by this
 * addon's LivingEntityMixin; a setback starts the speed again from nothing.
 * <p>
 * <b>No Durability</b>: the server wears an elytra by one once it has glided you for 20 ticks without a break, and
 * starts counting again whenever a tick passes without the glide. So every {@link #INTERVAL} ticks a chestplate from
 * your hotbar or off hand goes on just after the tick's movement has gone out (a click: NoCheatPlus takes one while you
 * glide, not while you sprint), and the elytra back on (used, and the glide started again) just before the movement
 * of the tick after next. You hold still for the tick between, so it sends no movement: the server's ticks between the
 * two stop the glide, and every movement it checks still finds the elytra on (NoCheatPlus checks one without it as
 * walking, and sets it back; it refuses a click while you move with the chestplate on). Two ticks apart, the swaps don't
 * reach the server in the same tick (where the restart, coming while it still glides you, would stop the glide). The
 * client keeps gliding through the server's stop meanwhile.
 * <p>
 * A glide lost in mid-air during a flight (the server set you back, or stopped it) starts again by itself, and sprint is
 * let go while you glide: it does nothing for a glide, and NoCheatPlus refuses No Durability's clicks while you sprint.
 */
public class ElytraControl extends Module {
	private final DoubleSetting speed = sgGeneral.doubleSetting("Speed")
		.description("How fast the movement keys fly you, in blocks a second.").defaultValue(20).range(1, 80).decimals(1).build();
	private final DoubleSetting verticalSpeed = sgGeneral.doubleSetting("Vertical Speed")
		.description("How fast jump climbs and sneak descends, in blocks a second.").defaultValue(10).range(0, 40).decimals(1).build();
	private final DoubleSetting sink = sgGeneral.doubleSetting("Sink")
		.description("How fast you drift down with neither jump nor sneak held, in blocks a second (0 holds your height).")
		.defaultValue(0).range(0, 20).decimals(2).build();
	private final BoolSetting noDurability = sgGeneral.bool("No Durability")
		.description("Keep the elytra from wearing out, by putting a chestplate from your hotbar or off hand on for a moment every so often.")
		.defaultValue(false).build();

	/** Client ticks the server glides you between two No Durability breaks: well under the 20 that wear the elytra. */
	private static final int INTERVAL = 12;
	/**
	 * A glide lost in mid-air is started again after this many ticks of the server saying it isn't gliding you (one is
	 * often just a No Durability break's stop arriving before its restart), and only this many ticks after the last
	 * glide: a jump of your own isn't one.
	 */
	private static final int REENGAGE_AFTER = 2, REENGAGE_WITHIN = 40;
	/** NoCheatPlus's slack over the vanilla glide it predicts, blocks a tick, less a margin. */
	private static final double SPEED_SLACK = 0.09;
	/** Vanilla's glide: the share of the speed steered towards the look each tick, and the speed a look up spends. */
	private static final double STEER = 0.1, CLIMB_COST = 0.04;

	/** The horizontal movement asked for last tick, blocks a tick: what this tick builds on. */
	private Vec3 flown = Vec3.ZERO;
	private volatile boolean setBack;
	/**
	 * No Durability: the chestplate went on after a tick's movement; the next tick holds still and sends none
	 * ({@link #frozen}), and the elytra goes back before the movement of the one after.
	 */
	private boolean chestOn, frozen;
	/** Ticks the server has glided you since the last break, and whether the client glides on through the breaks. */
	private int glideTicks;
	private boolean holdGlide, pairWarned;
	/** Ticks since you last glided in the air (large once you've landed), and ticks without the glide since. */
	private int sinceGlide = Integer.MAX_VALUE / 2;

	public ElytraControl() {
		super(Categories.MOVEMENT, "Elytra Control", "Fly the elytra with the movement keys: steady speed, no sinking. For loosely checked servers, not 2b2t.");
		conflictsWith(Recast.class, Altitude.class);
	}

	@Override
	protected void onEnable() {
		flown = Vec3.ZERO;
		setBack = false;
		chestOn = frozen = holdGlide = pairWarned = false;
		glideTicks = 0;
		sinceGlide = Integer.MAX_VALUE / 2;
	}

	@Override
	protected void onDisable() {
		if (chestOn && inGame()) ChestSwap.startGlide();
		chestOn = frozen = holdGlide = false;
	}

	/** The tick between No Durability's swaps sends no movement (a look changed would send one). */
	@Subscribe(packets = ServerboundMovePlayerPacket.class)
	private void onSendMove(PacketEvent.Send e) {
		if (frozen) e.cancel();
	}

	/** Whether the local player counts as gliding through No Durability's breaks. */
	boolean holdsGlide() {
		return holdGlide;
	}

	/** Whether the chest slot is No Durability's to swap: in the air with it on (Auto Armor leaves it alone). */
	boolean holdsChest() {
		return noDurability.get() && (chestOn || holdGlide);
	}

	/** Before this tick's movement: the elytra back on after a break, and the glide started again. */
	@Subscribe
	private void onTickStart(TickEvent.Pre e) {
		if (!inGame()) return;
		var p = mc.player;
		if (p.onGround() || p.isInWater() || p.isInLava() || !noDurability.get()) holdGlide = false;
		if (chestOn && !frozen) {
			frozen = true;
			return;
		}
		if (chestOn) {
			chestOn = frozen = false;
			glideTicks = 0;
			if (!ChestSwap.startGlide()) holdGlide = false;
			return;
		}
		reengage(p);
	}

	/** A glide lost in mid-air during a flight starts again, as vanilla starts one (the client first, then the server). */
	private void reengage(LocalPlayer p) {
		if (p.onGround() || p.isInWater() || p.isInLava() || p.isPassenger() || p.getAbilities().flying) {
			sinceGlide = Integer.MAX_VALUE / 2;
			return;
		}
		if (p.isFallFlying()) {
			sinceGlide = 0;
			return;
		}
		if (++sinceGlide < REENGAGE_AFTER || sinceGlide > REENGAGE_WITHIN) return;
		if (p.tryToStartFallFlying()) {
			Packets.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
			sinceGlide = 0;
		}
	}

	/** Sprint is let go while you glide. */
	@Subscribe
	private void onInput(InputEvent e) {
		var p = mc.player;
		if (p == null || !p.isFallFlying()) return;
		e.sprint = false;
		// Vanilla tells the server with this tick's movement.
		if (p.isSprinting()) p.setSprinting(false);
	}

	/** After this tick's movement has gone out: the chestplate on for the server's next tick, when a break is due. */
	@Subscribe
	private void onTickEnd(TickEvent.Post e) {
		if (!inGame() || !noDurability.get()) return;
		var p = mc.player;
		// Not while sprinting: NoCheatPlus refuses an inventory click then.
		if (!p.isFallFlying() || p.onGround() || p.isInWater() || !ChestSwap.elytraWorn() || p.isCreative() || p.isSprinting()) {
			glideTicks = 0;
			return;
		}
		if (++glideTicks < INTERVAL || !ChestSwap.settled()) return;
		if (!ChestSwap.ready()) {
			if (!pairWarned) warn("No Durability needs a chestplate in your hotbar or off hand.");
			pairWarned = true;
			return;
		}
		pairWarned = false;
		// A click, sent at once: a use now would be held for the next tick, and arrive with the elytra going back on.
		if (ChestSwap.clickChestplateOn()) chestOn = holdGlide = true;
	}

	@Override
	public String hudInfo() {
		return flown.lengthSqr() > 1.0E-6 ? String.format("%.0f b/s", flown.horizontalDistance() * 20) : null;
	}

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onSetBack(PacketEvent.Receive e) {
		setBack = true;
	}

	/** The glide's movement this tick, given vanilla's: unchanged unless this is on and the local player glides. */
	public static Vec3 glideMovement(Vec3 vanilla) {
		ElytraControl m = Modules.active(ElytraControl.class);
		if (m == null) return vanilla;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !p.isFallFlying() || p.isInWater() || p.isInLava()) {
			m.flown = Vec3.ZERO;
			return vanilla;
		}
		return m.fly(p);
	}

	private Vec3 fly(LocalPlayer p) {
		if (setBack) {
			setBack = false;
			flown = Vec3.ZERO;
			// The server may not be gliding you any more: let the client show what it says.
			holdGlide = false;
		}
		var keys = p.input.keyPresses;
		double ahead = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		double side = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		Vec3 wanted = Vec3.ZERO;
		if (ahead != 0 || side != 0) {
			float yaw = p.getYRot() * Mth.DEG_TO_RAD;
			double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
			// Along the heading and to its left (vanilla's own key directions), the same speed diagonally.
			wanted = new Vec3(-sin * ahead + cos * side, 0, cos * ahead + sin * side).normalize().scale(speed.get() / 20);
		}
		if (frozen) return Vec3.ZERO;
		// What the keys ask for, as fast as NoCheatPlus's elytra check allows: the vanilla glide from the last move
		// (a look up spends speed, then it's steered a tenth of the way to the look), plus its slack.
		double h = flown.horizontalDistance();
		Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
		Vec3 glide = flown.subtract(look.scale(h * Math.max(0, Mth.sin(-p.getXRot() * Mth.DEG_TO_RAD)) * CLIMB_COST));
		glide = glide.add(look.scale(h).subtract(glide).scale(STEER));
		double most = glide.horizontalDistance() + SPEED_SLACK;
		flown = wanted.length() > most ? wanted.normalize().scale(most) : wanted;
		// Not into chunks that haven't loaded: stop at their edge instead.
		Vec3 next = p.position().add(flown.scale(4));
		if (!p.level().hasChunk(Mth.floor(next.x) >> 4, Mth.floor(next.z) >> 4)) flown = Vec3.ZERO;

		double climb = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
		double y = climb != 0 ? climb * verticalSpeed.get() / 20 : -sink.get() / 20;
		return new Vec3(flown.x, y, flown.z);
	}
}
