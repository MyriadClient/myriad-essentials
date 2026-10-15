package dev.myriad.essentials.modules.movement;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Flies the elytra like a creative flight: while you glide, the keys move you and nothing else does. Forward, back and
 * the sides go along the camera's heading at Speed (built up at Acceleration a tick, and let down the same way when
 * you let go), jump climbs and sneak descends at Vertical Speed, and with neither you hold your height, sinking at
 * Sink. The elytra still opens the vanilla way (jump in the air). It doesn't fly into chunks that haven't loaded.
 * <p>
 * Made for servers that check elytra flight loosely (6b6t's NoCheatPlus, for one). One that simulates the glide, as
 * Grim does on 2b2t, sets this back from the first tick: use {@link Recast} or {@link Altitude} there. Applied by this
 * addon's LivingEntityMixin; a setback starts the speed again from nothing.
 */
public class ElytraControl extends Module {
	private final DoubleSetting speed = sgGeneral.doubleSetting("Speed")
		.description("How fast the movement keys fly you, in blocks a second.").defaultValue(20).range(1, 80).decimals(1).build();
	private final DoubleSetting acceleration = sgGeneral.doubleSetting("Acceleration")
		.description("How much speed (blocks a second) is gained or lost each tick, up to Speed or down to a stop.")
		.defaultValue(1.8).range(0.1, 80).decimals(1).build();
	private final DoubleSetting verticalSpeed = sgGeneral.doubleSetting("Vertical Speed")
		.description("How fast jump climbs and sneak descends, in blocks a second.").defaultValue(10).range(0, 40).decimals(1).build();
	private final DoubleSetting sink = sgGeneral.doubleSetting("Sink")
		.description("How fast you drift down with neither jump nor sneak held, in blocks a second (0 holds your height).")
		.defaultValue(0).range(0, 20).decimals(2).build();

	/** The horizontal movement asked for last tick, blocks a tick: what this tick builds on. */
	private Vec3 flown = Vec3.ZERO;
	private volatile boolean setBack;

	public ElytraControl() {
		super(Categories.MOVEMENT, "Elytra Control", "Fly the elytra with the movement keys: steady speed, no sinking. For loosely checked servers, not 2b2t.");
		conflictsWith(Recast.class, Altitude.class);
	}

	@Override
	protected void onEnable() {
		flown = Vec3.ZERO;
		setBack = false;
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
		// Towards what the keys ask for, by at most Acceleration this tick.
		Vec3 change = wanted.subtract(flown);
		double step = acceleration.get() / 20;
		flown = change.length() <= step ? wanted : flown.add(change.normalize().scale(step));
		// Not into chunks that haven't loaded: stop at their edge instead.
		Vec3 next = p.position().add(flown.scale(4));
		if (!p.level().hasChunk(Mth.floor(next.x) >> 4, Mth.floor(next.z) >> 4)) flown = Vec3.ZERO;

		double climb = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
		double y = climb != 0 ? climb * verticalSpeed.get() / 20 : -sink.get() / 20;
		return new Vec3(flown.x, y, flown.z);
	}
}
