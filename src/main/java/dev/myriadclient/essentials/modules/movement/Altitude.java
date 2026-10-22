package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.setting.EnumSetting;
import org.jetbrains.annotations.Nullable;

/**
 * Crosses open country without fireworks, "pitch 40" style. It dives until you're fast, pulls up hard, then eases back to
 * level: pulling up gives back more height than the speed it costs, so the cycle holds the altitude you started gliding
 * at (it dives harder when you're above it and climbs more when you're below). Start high, since the first dive from a
 * slow glide drops you 50 or so blocks. Steer with the camera, or turn on Lock Rotation to keep flying the way you faced
 * when the glide started (Snap rounds it to 45° or 90°); the flight's pitch (and a locked heading) only goes to the
 * server and the flight physics, so you can look around freely. With No Durability each glide start waits for the
 * server to have the elytra on; without it the elytra wears, and once it's down to what
 * landing from here takes, a spare from your inventory goes on in mid-air. Made to be left alone: when the server sets
 * you back a few times in a row it glides gently until the server agrees again, and it slows down (climbing) while the
 * chunks ahead haven't loaded; however often the server sets you back, it flies on (a dropped glide starts again), so
 * leave Auto Disconnect's Hard Deck on to catch a flight that goes wrong. When the last elytra is wearing out it comes
 * down to land, levelling out near the ground, and After Landing disconnects you there or leaves you standing. Core
 * flies it ({@code Myriad.elytra().altitude}); this steers it and decides what happens once it's down.
 */
public class Altitude extends FlightMode {
	public enum AfterLanding {
		STAY, DISCONNECT
	}

	private final EnumSetting<AfterLanding> afterLanding = sgGeneral.oneOf("After Landing", AfterLanding.DISCONNECT)
		.description("When it has to come down (the last elytra wearing out): disconnect once landed, or stay there.").visible(this::chosen).build();

	Altitude(ElytraFly fly) {
		super(fly, ElytraFly.ALTITUDE, Myriad.elytra()::altitude);
		flight.onLanded(this::landed);
	}

	/** Long fast glides: each start waits for the server to have the elytra back on (see ElytraSwap.Behaviour). */
	@Override
	protected boolean waitsForElytra() {
		return true;
	}

	@Override
	protected void steer() {
		flight.heading(heading(mc.player.getYRot()));
	}

	/** Down safely: After Landing disconnects you or leaves you there. */
	private void landed(@Nullable Why why) {
		String text = why == null ? "Landed" : "Landed: " + why.detail();
		if (afterLanding.get() == AfterLanding.DISCONNECT) leave(text);
		else warn(text);
	}
}
