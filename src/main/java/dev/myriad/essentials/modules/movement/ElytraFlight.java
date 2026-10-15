package dev.myriad.essentials.modules.movement;

import dev.myriad.api.Myriad;
import dev.myriad.api.module.Modules;
import dev.myriad.essentials.modules.combat.AutoArmor;
import net.minecraft.client.Minecraft;

/**
 * Who is flying the elytra, for this addon's mixins and the modules that keep out of its way: {@link Recast} or
 * {@link Altitude} (one at a time), or, with neither on, Auto Armor's No Durability Elytra for flying yourself.
 */
public final class ElytraFlight {
	private ElytraFlight() {
	}

	/** The flight module that's on, or null. */
	private static ElytraModule flying() {
		ElytraModule m = Modules.active(Recast.class);
		return m != null ? m : Modules.active(Altitude.class);
	}

	/**
	 * Whether Recast, Altitude or Elytra Control is on: the first two do their own swapping, and Elytra Control's flight
	 * has no glide to restart, so Auto Armor's No Durability Elytra stands down for all three.
	 */
	public static boolean flightModuleOn() {
		return flying() != null || Modules.active(ElytraControl.class) != null;
	}

	/** Whether flight physics should use the flight module's rotation instead of the camera's. */
	public static boolean spoofing() {
		ElytraModule m = flying();
		return m != null && m.spoofing;
	}

	/**
	 * The flight rotation, as this tick's movement packet carries it (called from the movement code, before it goes out):
	 * the yaw kept continuous with the last one sent, and a pitch nudged when nothing else changed. Grim simulates those
	 * exact floats, so the physics uses them too; the plain lane angles drift from them by a hair, enough to flag once
	 * something else (Baritone's turns, for one) has wound the sent yaw far from zero. Only meaningful while
	 * {@link #spoofing()}.
	 */
	public static float spoofYaw() {
		return Myriad.rotations().rotationForAction()[0];
	}

	public static float spoofPitch() {
		return Myriad.rotations().rotationForAction()[1];
	}

	/** Whether Recast is bouncing: it lets the glide drop and opens it again itself, every hop. */
	public static boolean bouncing() {
		Recast m = Modules.active(Recast.class);
		return m != null && m.bouncing();
	}

	/** Whether the local player should count as gliding even though the elytra closed (Recast's landings). */
	public static boolean holdsGlide() {
		ElytraModule m = flying();
		return m != null && m.holdGlide;
	}

	/** Whether to keep sprinting while gliding (see {@link Recast#holdsSprint}). */
	public static boolean holdsSprint() {
		Recast m = Modules.active(Recast.class);
		return m != null && m.holdsSprint();
	}

	/** Whether Recast has you on foot for now (walking round or clearing something, waiting): no gliding meanwhile. */
	public static boolean onFoot() {
		Recast m = Modules.active(Recast.class);
		return m != null && m.onFoot();
	}

	/** Whether No Durability is swapping for whoever flies: Recast's or Altitude's own, or Auto Armor's for you. */
	public static boolean noDurability() {
		ElytraModule m = flying();
		if (m != null) return m.noDurability();
		AutoArmor armor = Modules.active(AutoArmor.class);
		return armor != null && armor.noDurabilityElytra();
	}

	/**
	 * Whether the chest slot is No Durability's (or the bounce's) to swap: while it swaps, and with No Durability on any
	 * time you're off the ground, as a chestplate worn then is one of its swaps. Auto Armor leaves the slot alone
	 * meanwhile: putting the best elytra on over it would wear each of your elytras in turn.
	 */
	public static boolean holdsChest() {
		ElytraModule m = flying();
		if (m != null && (m.noDur.swapping() || m.swapsChest())) return true;
		AutoArmor armor = Modules.active(AutoArmor.class);
		if (m == null && armor != null && armor.elytraSwapping()) return true;
		var p = Minecraft.getInstance().player;
		return noDurability() && p != null && !p.onGround() && !p.isInWater();
	}
}
