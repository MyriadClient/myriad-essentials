package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static dev.myriadclient.essentials.gametest.ElytraScenes.elytraDamage;
import static dev.myriadclient.essentials.gametest.ElytraScenes.launch;
import static dev.myriadclient.essentials.gametest.ElytraScenes.settleAfterNewTerrain;

/**
 * Elytra Tweaks when you fly yourself: gliding without the elytra wearing, a chestplate in the hotbar to
 * swap with, and the landing afterwards costing no health.
 */
public final class NoDurabilityTests extends EssentialsSuite {
	private static final String SWAP = "myriad-essentials:elytra_tweaks", ARMOR = "myriad-essentials:auto_armor";

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void elytra(TestContext t) {
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		launch(t, 2000.5, 200, 0.5);
		t.waitTicks(30);
		t.flagsFromHere();
		// Elytra Tweaks when you fly yourself (Altitude has breaks instead). The elytra opened as a player does, jump pressed
		// while falling, then gliding on its own, looking a little down. What it wore before Elytra Tweaks was on doesn't count.
		t.module(SWAP).disable();
		t.player().look(-90, 10);
		t.hold(Key.JUMP).forTicks(2);
		t.waitTicks(2);
		int before = elytraDamage(t);
		t.module(ARMOR).enable();
		t.module(SWAP).enable();
		t.waitTicks(200);
		t.module(ARMOR).disable();
		t.module(SWAP).disable();
		t.waitTicks(20);
		int damage = elytraDamage(t) - before;
		float hp = t.player().health();
		double x = t.player().serverPosition().x;
		String detail = String.format(Locale.ROOT, "elytra damage %d after 10 s of flight, %.0f blocks, health %.0f", damage, x - 2000, hp);
		t.check(0 <= damage && damage <= 2 && hp > 0 && x > 2060, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 3000)
	public void elytraLanding(TestContext t) {
		// Elytra Tweaks keeps the server from gliding you, so it counts every block you come down as a fall; gliding down
		// to the ground afterwards must not cost any of it.
		// Force-loaded chunks take the fill without anyone there; then stand on the floor while setting up.
		t.arena().forceload(3990, -24, 4400, 24);
		t.waitTicks(20);
		t.arena().fill(3990, 50, -20, 4400, 50, 20, "stone");
		t.player().at(4000.5, 51, 10.5, -90, 0);
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		t.module(SWAP).enable();
		t.player().look(-90, 0);
		launch(t, 4000.5, 130, 10.5);
		// Altitude flies a while, then you glide down yourself, Elytra Tweaks's own swapping taking over.
		t.module(ARMOR).enable();
		t.module("myriad-essentials:elytra_fly").set("type", "altitude").enable();
		t.waitTicks(120);
		t.module("myriad-essentials:elytra_fly").disable();
		t.player().look(-90, 25);
		t.waitTicks(200);
		t.module(ARMOR).disable();
		t.module(SWAP).disable();
		t.waitTicks(10);
		Vec3 p = t.player().serverPosition();
		float hp = t.player().health();
		// Dying puts you back at spawn, at full health: only a landing out here counts.
		String detail = String.format(Locale.ROOT, "landed at y=%.1f after %.0f blocks, health %.1f", p.y, p.x - 4000, hp);
		t.check(hp >= 20 && p.y < 52 && p.x > 4000, detail);
		t.note(detail);
	}

	/**
	 * A minute of flying yourself with Elytra Tweaks (No Durability) far out (the same stretch each run, the
	 * chunks late with the server's chunk sending slowed down, as tools/grim-test's can be): rockets every 1.5 s
	 * ({@code rockets}) or a long glide down from high up. The glide must never drop on your screen (gliding, then not,
	 * in mid-air), the elytra must not wear, and Grim must take it all.
	 */
	private static void longFlight(TestContext t, boolean rockets) {
		longFlight(t, rockets, true);
	}

	private static void longFlight(TestContext t, boolean rockets, boolean noDurability) {
		double x = 3_000_000.5, z = 3_000_000.5 + (rockets ? 0 : 50_000) + (noDurability ? 0 : 100_000);
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		if (rockets) t.player().give(2, "firework_rocket", 64);
		t.player().select(2);
		launch(t, x, rockets ? 160 : 300, z);
		t.waitTicks(40);
		t.flagsFromHere();
		t.module(SWAP).disable().set("no_durability", String.valueOf(noDurability));
		t.player().look(-90, rockets ? -15 : 12);
		t.hold(Key.JUMP).forTicks(2);
		t.waitTicks(4);
		int before = elytraDamage(t);
		t.module(ARMOR).enable();
		t.module(SWAP).enable();
		int[] drops = {0}, glidingTicks = {0};
		boolean[] was = {false};
		for (int tick = 0; tick < 1200; tick++) {
			if (rockets && tick % 30 == 0) t.step("use");
			boolean[] now = t.onGame(() -> {
				var p = net.minecraft.client.Minecraft.getInstance().player;
				return new boolean[] {p.isFallFlying(), p.onGround() || p.isInWater()};
			});
			if (now[1]) break;
			if (was[0] && !now[0]) {
				drops[0]++;
				final int at = tick;
				t.note("drop at tick " + at + ": " + t.onGame(() -> {
					var mc = net.minecraft.client.Minecraft.getInstance();
					var p = mc.player;
					var v = p.getDeltaMovement();
					StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "speed %.2f, v.y %.2f, chest %s; chunks here/+5/+10/+20 ticks:", v.length(), v.y,
						p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem()));
					for (int k : new int[] {0, 5, 10, 20}) sb.append(' ').append(mc.level.getChunkSource().hasChunk((int) Math.floor(p.getX() + v.x * k) >> 4,
						(int) Math.floor(p.getZ() + v.z * k) >> 4) ? "y" : "n");
					return sb.toString();
				}));
			}
			if (now[0]) glidingTicks[0]++;
			was[0] = now[0];
			t.waitTicks(1);
		}
		t.module(ARMOR).disable();
		t.module(SWAP).disable();
		t.waitTicks(20);
		int damage = elytraDamage(t) - before;
		Vec3 p = t.player().serverPosition();
		String detail = String.format(Locale.ROOT, "%d glide drops on screen, %d ticks gliding, elytra damage %d, %.0f blocks, y=%.0f, health %.0f",
			drops[0], glidingTicks[0], damage, p.x - x, p.y, t.player().health());
		t.module(SWAP).set("no_durability", "true");
		t.check(drops[0] == 0 && (!noDurability || 0 <= damage && damage <= 2) && glidingTicks[0] > 600, detail);
		t.note(detail);
		settleAfterNewTerrain(t);
	}

	@Test(name = "rocket_flight", tags = "elytra", timeoutTicks = 2000)
	public void rocketFlight(TestContext t) {
		longFlight(t, true);
	}

	/** {@link #longGlide} with No Durability off: what the glide does by itself, to tell its drops from No Durability's. */
	@Test(name = "long_glide_plain", tags = "elytra", timeoutTicks = 2000)
	public void longGlidePlain(TestContext t) {
		longFlight(t, false, false);
	}

	@Test(name = "long_glide", tags = "elytra", timeoutTicks = 2000)
	public void longGlide(TestContext t) {
		longFlight(t, false);
	}
}
