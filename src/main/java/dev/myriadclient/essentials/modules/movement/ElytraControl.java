package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.GlideEvent;
import dev.myriadclient.api.setting.DoubleSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra Fly's Control: flies the elytra like a creative flight. While you glide, the keys move you and nothing else
 * does: forward, back and the sides go along the camera's heading at Speed (or, while {@link LockRotation} holds one,
 * the locked heading, sent to the server while the camera looks where you like; {@link AutoWalk} holds forward for
 * you), jump climbs and sneak descends at Vertical Speed, and with neither you hold your height, sinking at Sink.
 * Turning and slowing down are instant; speed builds up
 * as fast as NoCheatPlus's elytra check allows (it predicts a vanilla glide from your last move and your look, and
 * takes up to 0.1 blocks a tick more, whatever the direction), about 1.8 blocks a second each tick. The elytra still
 * opens the vanilla way (jump in the air). It doesn't fly into chunks that haven't loaded.
 * <p>
 * Only for servers that don't simulate the glide (6b6t's NoCheatPlus, singleplayer): Grim (2b2t) sets it back from the
 * first tick, so Elytra Fly doesn't offer it there. Applied through core's GlideEvent; a setback starts the speed again
 * from nothing.
 */
public class ElytraControl extends ElytraMode {
	private final DoubleSetting speed = sgGeneral.doubleSetting("Speed")
		.description("How fast the movement keys fly you, in blocks a second.").defaultValue(20).range(1, 80).decimals(1).visible(this::chosen).build();
	private final DoubleSetting verticalSpeed = sgGeneral.doubleSetting("Vertical Speed")
		.description("How fast jump climbs and sneak descends, in blocks a second.").defaultValue(10).range(0, 40).decimals(1).visible(this::chosen).build();
	private final DoubleSetting sink = sgGeneral.doubleSetting("Sink")
		.description("How fast you drift down with neither jump nor sneak held, in blocks a second (0 holds your height).")
		.defaultValue(0).range(0, 20).decimals(2).visible(this::chosen).build();

	/** NoCheatPlus's slack over the vanilla glide it predicts, blocks a tick, less a margin. */
	private static final double SPEED_SLACK = 0.09;
	/** Vanilla's glide: the share of the speed steered towards the look each tick, and the speed a look up spends. */
	private static final double STEER = 0.1, CLIMB_COST = 0.04;

	/** The horizontal movement asked for last tick, blocks a tick: what this tick builds on. */
	private Vec3 flown = Vec3.ZERO;

	ElytraControl(ElytraFly fly) {
		super(fly, ElytraFly.CONTROL);
	}

	@Override
	protected void resetFlight() {
		flown = Vec3.ZERO;
	}

	@Override
	protected void tickFlight() {
		// A setback: the speed starts again from nothing.
		if (flagged) {
			flagged = false;
			flown = Vec3.ZERO;
		}
		var p = mc.player;
		// With Lock Rotation holding a heading, the server sees the heading flown (NoCheatPlus predicts the glide from
		// your look); the camera stays yours.
		if (LockRotation.holding()) spoof(heading(p), p.getXRot());
		else spoofing = false;
	}

	/** The yaw the keys move you relative to: Lock Rotation's heading (snapped), or the camera's. */
	private float heading(LocalPlayer p) {
		return heading(p.getYRot());
	}

	@Override
	public String hudInfo() {
		return flown.lengthSqr() > 1.0E-6 ? String.format("%.0f b/s", flown.horizontalDistance() * 20) : null;
	}

	@Subscribe
	private void onGlide(GlideEvent e) {
		Vec3 flown = mc.player != null ? glideMovement(mc.player) : null;
		if (flown != null) e.setVelocity(flown);
	}

	/** The glide's movement this tick: what the keys ask for (vanilla's is replaced); null in water or lava. */
	Vec3 glideMovement(LocalPlayer p) {
		if (p.isInWater() || p.isInLava()) {
			flown = Vec3.ZERO;
			return null;
		}
		var keys = p.input.keyPresses;
		double ahead = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		double side = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		Vec3 wanted = Vec3.ZERO;
		if (ahead != 0 || side != 0) {
			float yaw = heading(p) * Mth.DEG_TO_RAD;
			double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
			// Along the heading and to its left (vanilla's own key directions), the same speed diagonally.
			wanted = new Vec3(-sin * ahead + cos * side, 0, cos * ahead + sin * side).normalize().scale(speed.get() / 20);
		}
		// What the keys ask for, as fast as NoCheatPlus's elytra check allows: the vanilla glide from the last move
		// (a look up spends speed, then it's steered a tenth of the way to the look), plus its slack.
		double h = flown.horizontalDistance();
		Vec3 look = Vec3.directionFromRotation(0, heading(p));
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
