package dev.myriad.essentials.modules.movement;

import dev.myriad.api.Myriad;
import dev.myriad.api.module.Modules;
import dev.myriad.essentials.modules.combat.AutoArmor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Who is flying the elytra, for this addon's mixins and the modules that keep out of its way: {@link ElytraFly}'s type
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

	/** Whether flight physics should use the flight's rotation instead of the camera's. */
	public static boolean spoofing() {
		ElytraMode m = flying();
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
		return flying() instanceof Recast r && r.bouncing();
	}

	/**
	 * Whether the local player should count as gliding even though the elytra closed: Recast's landings, and No
	 * Durability's breaks where the glide is stopped and started (see {@link NoDurability}).
	 */
	public static boolean holdsGlide() {
		ElytraMode m = flying();
		return m != null && m.holdGlide || NoDurability.holdsGlide();
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

	/**
	 * The movement the local player's glide makes this tick, given vanilla's (Rocket Boost's comes after): none while a
	 * No Durability break holds you still (the velocity kept for the tick after), Control's while it flies, else null
	 * for vanilla's.
	 */
	public static Vec3 glideMovement(Vec3 vanilla) {
		if (NoDurability.holdsStill()) return Vec3.ZERO;
		var p = Minecraft.getInstance().player;
		if (p != null && flying() instanceof ElytraControl c) return c.glideMovement(p);
		return null;
	}

	/** Whether the movement {@link #glideMovement} gave is this tick's velocity too (Control's), not just a pause. */
	public static boolean glideSetsVelocity() {
		return !NoDurability.holdsStill() && flying() instanceof ElytraControl;
	}

	/** Recast is bouncing: the camera leaves out sprint's widening of the view (the bounce holds sprint, and lets go). */
	public static boolean steadyView() {
		return bouncing();
	}
}
