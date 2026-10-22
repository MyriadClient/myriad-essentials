package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.GlideEvent;
import dev.myriadclient.api.setting.DoubleSetting;

/**
 * Elytra Fly's Control: flies the elytra like a creative flight. While you glide, the keys move you and nothing else
 * does: forward, back and the sides go along the camera's heading at Speed (or, while {@link LockRotation} holds one,
 * the locked heading; {@link AutoWalk} holds forward for you), jump climbs and sneak descends at Vertical Speed, and
 * with neither you hold your height, sinking at Sink. The server sees you look the way you fly, level; the camera looks
 * where you like.
 * Turning and slowing down are instant; speed builds up
 * as fast as NoCheatPlus's elytra check allows (it predicts a vanilla glide from your last move and your look, and
 * takes up to 0.1 blocks a tick more, whatever the direction), about 1.8 blocks a second each tick. The elytra still
 * opens the vanilla way (jump in the air). It doesn't fly into chunks that haven't loaded.
 * <p>
 * Only for servers that don't simulate the glide (6b6t's NoCheatPlus, singleplayer): Grim (2b2t) sets it back from the
 * first tick, so Elytra Fly doesn't offer it there. Core flies it ({@code Myriad.elytra().control}); this sets its
 * heading, speed and climb from the keys. A setback starts the speed again from nothing. Like Altitude, it comes down
 * to land when the last elytra is wearing out.
 */
public class ElytraControl extends FlightMode {
	private final DoubleSetting speed = sgGeneral.decimal("Speed")
		.description("How fast the movement keys fly you, in blocks a second.").defaultValue(20).range(1, 80).decimals(1).visible(this::chosen).build();
	private final DoubleSetting verticalSpeed = sgGeneral.decimal("Vertical Speed")
		.description("How fast jump climbs and sneak descends, in blocks a second.").defaultValue(10).range(0, 40).decimals(1).visible(this::chosen).build();
	private final DoubleSetting sink = sgGeneral.decimal("Sink")
		.description("How fast you drift down with neither jump nor sneak held, in blocks a second (0 holds your height).")
		.defaultValue(0).range(0, 20).decimals(2).visible(this::chosen).build();

	ElytraControl(ElytraFly fly) {
		super(fly, ElytraFly.CONTROL, Myriad.elytra()::control);
		flight.onLanded(why -> warn(why == null ? "Landed" : "Landed: " + why.detail()));
	}

	@Override
	protected void steer() {
		var p = mc.player;
		var keys = p.input.keyPresses;
		double ahead = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		double side = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		boolean moving = ahead != 0 || side != 0;
		// Along the heading and to its left (vanilla's own key directions), the same speed diagonally. Only while gliding:
		// the elytra opens the vanilla way (jump in the air), and the flight doesn't take off by itself.
		float yaw = heading(p.getYRot()) + (moving ? (float) Math.toDegrees(Math.atan2(-side, ahead)) : 0);
		double climb = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
		flight.heading(yaw).speed(moving && p.isFallFlying() ? speed.get() : 0)
			.climb(climb != 0 ? climb * verticalSpeed.get() : -sink.get());
	}

	/** The keys as they are when the glide moves, before core's flight works out the movement. */
	@Subscribe(priority = Priority.HIGH)
	private void onGlide(GlideEvent e) {
		if (inGame()) steer();
	}
}
