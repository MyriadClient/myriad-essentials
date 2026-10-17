package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.module.Modules;
import net.minecraft.client.Minecraft;

/**
 * Who is flying the elytra, for this addon's mixins and the modules that keep out of its way (core's Elytra service
 * steers the glide and holds it open for them; see ElytraMode): {@link ElytraFly}'s type
 * ({@link Recast}, {@link Altitude}, {@link ElytraControl}), and {@link ElytraSwap}, which swaps for whoever flies.
 */
public final class ElytraFlight {
	private ElytraFlight() {
	}

	/** The type flying, or null. */
	private static ElytraMode flying() {
		ElytraFly m = Modules.active(ElytraFly.class);
		return m != null ? m.flying() : null;
	}

	/** Whether Elytra Fly is on: its types run their own swapping, and Elytra Swap's for you stands down. */
	public static boolean flightModuleOn() {
		return Modules.active(ElytraFly.class) != null;
	}

	/** Whether Recast is bouncing: it lets the glide drop and opens it again itself, every hop. */
	public static boolean bouncing() {
		return flying() instanceof Recast r && r.bouncing();
	}

	/** Whether to keep sprinting while gliding (see {@link Recast#holdsSprint}). */
	public static boolean holdsSprint() {
		return flying() instanceof Recast r && r.holdsSprint();
	}

	/** Whether Recast has you on foot for now (walking round or clearing something, waiting): no gliding meanwhile. */
	public static boolean onFoot() {
		return flying() instanceof Recast r && r.onFoot();
	}

	/** Whether Elytra Swap swaps for whoever flies: Elytra Fly's type, or you. */
	public static boolean noDurability() {
		ElytraMode m = flying();
		if (m != null) return m.noDurability();
		ElytraSwap swap = Modules.active(ElytraSwap.class);
		return swap != null && swap.swapsForYou();
	}

	/**
	 * Whether the chest slot is Elytra Swap's (or the bounce's) to swap: while it swaps, and with Elytra Swap on any
	 * time you're off the ground, as a chestplate worn then is one of its swaps (or, where it breaks the glide without
	 * one, the elytra it needs). Auto Armor leaves the slot alone meanwhile: putting the best elytra on over it would
	 * wear each of your elytras in turn.
	 */
	public static boolean holdsChest() {
		ElytraMode m = flying();
		if (m != null && (m.noDur.swapping() || m.swapsChest())) return true;
		ElytraSwap swap = Modules.active(ElytraSwap.class);
		if (m == null && swap != null && swap.swappingForYou()) return true;
		var p = Minecraft.getInstance().player;
		return noDurability() && p != null && !p.onGround() && !p.isInWater();
	}

	/** Recast is bouncing: the camera leaves out sprint's widening of the view (the bounce holds sprint, and lets go). */
	public static boolean steadyView() {
		return bouncing();
	}
}
