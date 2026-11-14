package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static dev.myriadclient.essentials.gametest.ElytraScenes.elytraDamage;
import static dev.myriadclient.essentials.gametest.ElytraScenes.launch;

/**
 * Elytra Swap when you fly yourself (When You Fly): gliding without the elytra wearing, a chestplate in the hotbar to
 * swap with, and the landing afterwards costing no health.
 */
public final class NoDurabilityTests extends EssentialsSuite {
	private static final String SWAP = "myriad-essentials:elytra_swap", ARMOR = "myriad-essentials:auto_armor";

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void elytra(TestContext t) {
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		launch(t, 2000.5, 200, 0.5);
		t.waitTicks(30);
		t.flagsFromHere();
		// Elytra Swap when you fly yourself (Altitude has breaks instead). The elytra opened as a player does, jump pressed
		// while falling, then gliding on its own, looking a little down. What it wore before Elytra Swap was on doesn't count.
		t.module(SWAP).disable().set("when_you_fly", "true");
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
		// Elytra Swap keeps the server from gliding you, so it counts every block you come down as a fall; gliding down
		// to the ground afterwards must not cost any of it.
		// Force-loaded chunks take the fill without anyone there; then stand on the floor while setting up.
		t.arena().forceload(3990, -24, 4400, 24);
		t.waitTicks(20);
		t.arena().fill(3990, 50, -20, 4400, 50, 20, "stone");
		t.player().at(4000.5, 51, 10.5, -90, 0);
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		t.module(SWAP).enable().set("when_you_fly", "true");
		t.player().look(-90, 0);
		launch(t, 4000.5, 130, 10.5);
		// Altitude flies a while, then you glide down yourself, Elytra Swap's own swapping (When You Fly) taking over.
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
}
