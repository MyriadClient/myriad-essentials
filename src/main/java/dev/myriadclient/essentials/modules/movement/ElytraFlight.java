package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.combat.AutoArmor;
import net.minecraft.client.Minecraft;

/**
 * Who is flying the elytra, for this addon's mixins and the modules that keep out of its way (core's Elytra service
 * steers the glide and holds it open for them; see ElytraMode): {@link ElytraFly}'s type
 * ({@link Recast}, {@link Altitude}, {@link ElytraControl}), or, with Elytra Fly off, Auto Armor's No Durability
 * Elytra for flying yourself.
 */
public final class ElytraFlight {
	private ElytraFlight() {
	}

	/** The type flying, or null. */
	private static ElytraMode flying() {
		ElytraFly m = Modules.active(ElytraFly.class);
		return m != null ? m.flying() : null;
	}

	/** Whether Elytra Fly is on: its types do their own swapping, and Auto Armor's No Durability Elytra stands down. */
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

	/** Whether No Durability is swapping for whoever flies: Elytra Fly's, or Auto Armor's for you. */
	public static boolean noDurability() {
		ElytraMode m = flying();
		if (m != null) return m.noDurability();
		AutoArmor armor = Modules.active(AutoArmor.class);
		return armor != null && armor.noDurabilityElytra();
	}

	/**
	 * Whether the chest slot is No Durability's (or the bounce's) to swap: while it swaps, and with No Durability on any
	 * time you're off the ground, as a chestplate worn then is one of its swaps (or, where it breaks the glide without
	 * one, the elytra it needs). Auto Armor leaves the slot alone meanwhile: putting the best elytra on over it would
	 * wear each of your elytras in turn.
	 */
	public static boolean holdsChest() {
		ElytraMode m = flying();
		if (m != null && (m.noDur.swapping() || m.swapsChest())) return true;
		AutoArmor armor = Modules.active(AutoArmor.class);
		if (m == null && armor != null && armor.elytraSwapping()) return true;
		var p = Minecraft.getInstance().player;
		return noDurability() && p != null && !p.onGround() && !p.isInWater();
	}

	/** Recast is bouncing: the camera leaves out sprint's widening of the view (the bounce holds sprint, and lets go). */
	public static boolean steadyView() {
		return bouncing();
	}
}
