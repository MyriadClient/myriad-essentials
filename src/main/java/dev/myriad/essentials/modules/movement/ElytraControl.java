package dev.myriad.essentials.modules.movement;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.Myriad;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.util.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/**
 * Flies the elytra like a creative flight: while you glide, the keys move you and nothing else does. Forward, back and
 * the sides go along the camera's heading at Speed, jump climbs and sneak descends at Vertical Speed, and with neither
 * you hold your height, sinking at Sink. Turning and slowing down are instant; speed builds up as fast as NoCheatPlus's
 * elytra check allows (it predicts a vanilla glide from your last move and your look, and takes up to 0.1 blocks a tick
 * more, whatever the direction), about 1.8 blocks a second each tick. The elytra still opens the vanilla way (jump in
 * the air). It doesn't fly into chunks that haven't loaded, and a glide lost in mid-air during a flight starts again by
 * itself.
 * <p>
 * Made for servers that check elytra flight loosely (6b6t's NoCheatPlus, for one). One that simulates the glide, as
 * Grim does on 2b2t, sets this back from the first tick: use {@link Recast} or {@link Altitude} there. Applied by this
 * addon's LivingEntityMixin; a setback starts the speed again from nothing.
 * <p>
 * <b>No Durability</b>: the server wears an elytra by one once it has glided you for 20 ticks without a break, and
 * starts counting again after any of its ticks ends with you not gliding. A glide start sent while it glides you
 * stops the glide there and then (vanilla's answer to one it can't start). So every {@link #INTERVAL} ticks one goes
 * out to stop the glide, you hold still for that tick (no movement goes out while the server isn't gliding you: it
 * would check that as walking), and another starts it again before the next tick's movement. A server tick between
 * the two resets the count; if none falls between them (they arrive within one), that break is lost, but nothing is
 * set back: the stop doesn't wait for a tick, so the start always finds the glide stopped. A break the server took
 * shows: its tick ended with you not gliding, so it says so. One it doesn't say so for within a round trip is taken
 * as lost, and another follows at once. No chestplate needed.
 * Swapping a chestplate on for the break instead (as {@link NoDurability} does on 2b2t) failed one break in ten to
 * fifteen on 6b6t: with both swaps in one server tick, the start comes while it still glides you, and stops the glide.
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
		.description("Keep the elytra from wearing out, by stopping and starting the glide for a moment every so often.")
		.defaultValue(false).build();

	/**
	 * Ticks the server glides you between two No Durability breaks: under the 20 that wear the elytra, with room for a
	 * lost break to be noticed and made again. Each break holds you still a tick.
	 */
	private static final int INTERVAL = 12;
	/** Ticks past a round trip to wait for the server to say a break stopped the glide. */
	private static final int CONFIRM_MARGIN = 2;
	/** Vanilla's gliding bit in the entity flag byte ({@code Entity.FLAG_FALL_FLYING}). */
	private static final int GLIDING_BIT = 1 << 7;
	/**
	 * A glide lost in mid-air is started again after this many ticks of the server saying it isn't gliding you, and
	 * only this many ticks after the last glide: a jump of your own isn't one.
	 */
	private static final int REENGAGE_AFTER = 2, REENGAGE_WITHIN = 40;
	/** NoCheatPlus's slack over the vanilla glide it predicts, blocks a tick, less a margin. */
	private static final double SPEED_SLACK = 0.09;
	/** Vanilla's glide: the share of the speed steered towards the look each tick, and the speed a look up spends. */
	private static final double STEER = 0.1, CLIMB_COST = 0.04;

	/** The horizontal movement asked for last tick, blocks a tick: what this tick builds on. */
	private Vec3 flown = Vec3.ZERO;
	private volatile boolean setBack;
	/** No Durability: the glide was stopped at the start of this tick, which holds still (it starts again next tick). */
	private boolean stopped;
	/** Ticks the server has glided you since the last break, and whether the client glides on through the breaks. */
	private int glideTicks;
	private boolean holdGlide;
	/** Ticks left to hear that the last break stopped the glide (0: not waiting); and whether the server said so. */
	private int confirmTicks;
	private volatile boolean stopSeen;
	/** Ticks since you last glided in the air (large once you've landed). */
	private int sinceGlide = Integer.MAX_VALUE / 2;

	public ElytraControl() {
		super(Categories.MOVEMENT, "Elytra Control", "Fly the elytra with the movement keys: steady speed, no sinking. For loosely checked servers, not 2b2t.");
		conflictsWith(Recast.class, Altitude.class);
	}

	@Override
	protected void onEnable() {
		flown = Vec3.ZERO;
		setBack = stopped = holdGlide = stopSeen = false;
		glideTicks = confirmTicks = 0;
		sinceGlide = Integer.MAX_VALUE / 2;
	}

	@Override
	protected void onDisable() {
		if (stopped && inGame()) startGlide();
		stopped = holdGlide = false;
	}

	/** Whether the local player counts as gliding through No Durability's breaks. */
	boolean holdsGlide() {
		return holdGlide;
	}

	/** Whether the chest slot is to be left alone: it holds the elytra the breaks need (Auto Armor waits). */
	boolean holdsChest() {
		return noDurability.get() && holdGlide;
	}

	/**
	 * Before this tick's movement: the glide started again after a break, or a break begun (when one is due), or a glide
	 * lost in mid-air started again.
	 */
	@Subscribe
	private void onTickStart(TickEvent.Pre e) {
		if (!inGame()) return;
		var p = mc.player;
		if (p.onGround() || p.isInWater() || p.isInLava() || !noDurability.get()) holdGlide = false;
		if (stopped) {
			stopped = false;
			glideTicks = 0;
			startGlide();
			return;
		}
		if (breakDue(p)) {
			// Gliding, so the server takes this as a stop.
			stopSeen = false;
			startGlide();
			stopped = holdGlide = true;
			confirmTicks = Myriad.server().ping() / 50 + CONFIRM_MARGIN;
			return;
		}
		reengage(p);
	}

	/** Whether No Durability's break is due: gliding long enough, in the air, with an elytra that wears. */
	private boolean breakDue(LocalPlayer p) {
		var chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if (!noDurability.get() || !p.isFallFlying() || p.onGround() || p.isInWater() || p.isCreative()
			|| !ItemInfo.isGlider(chest) || !chest.isDamageableItem()) {
			glideTicks = 0;
			return false;
		}
		glideTicks++;
		// The last break unheard of: lost (its starts reached the server within one tick), so another one now.
		if (confirmTicks > 0 && --confirmTicks == 0 && !stopSeen) return true;
		return glideTicks >= INTERVAL;
	}

	/** Network thread: the server says it isn't gliding you, which a break that took shows. */
	@Subscribe(packets = ClientboundSetEntityDataPacket.class)
	private void onEntityData(PacketEvent.Receive e) {
		var p = mc.player;
		if (p == null || ((ClientboundSetEntityDataPacket) e.packet()).id() != p.getId()) return;
		for (var value : ((ClientboundSetEntityDataPacket) e.packet()).packedItems()) {
			if (value.id() == 0 && value.value() instanceof Byte flags && (flags & GLIDING_BIT) == 0) stopSeen = true;
		}
	}

	private void startGlide() {
		Packets.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
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
			startGlide();
			sinceGlide = 0;
		}
	}

	/** The tick of a break sends no movement (a look changed would send one): the server isn't gliding you. */
	@Subscribe(packets = ServerboundMovePlayerPacket.class)
	private void onSendMove(PacketEvent.Send e) {
		if (stopped) e.cancel();
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
		if (stopped) return Vec3.ZERO;
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
