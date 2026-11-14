package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static dev.myriadclient.essentials.gametest.ElytraScenes.elytraDamage;
import static dev.myriadclient.essentials.gametest.ElytraScenes.launch;

/**
 * Elytra Fly's Altitude: flights across open country from high above it, each far out along +x on a stretch of its own,
 * with Preserve swapping a worn elytra for a fresh one, Elytra Swap keeping the elytra from wearing, a landing before the
 * elytra breaks, and Rotation Lock holding the heading while the camera turns.
 */
public final class AltitudeTests extends EssentialsSuite {
	private static final String FLY = "myriad-essentials:elytra_fly", SWAP = "myriad-essentials:elytra_swap";

	/** The damage of the item in slot {@code slot} (slots as the API numbers them); -1 if it's empty. */
	private static int damage(TestContext t, int slot) {
		return t.player().item(slot).isEmpty() ? -1 : t.player().damage(slot);
	}

	// Flaky in the old suite: the worn elytra's wear lands on 4 now and then against a limit of 3 (1 run in 8 on core
	// 7875627 too).
	@Test(tags = "elytra", timeoutTicks = 1800)
	public void preserve(TestContext t) {
		// Flying with an elytra 12 uses from wearing out and a fresh one carried: swapped in mid-air, still flying.
		t.player().give(38, "elytra[damage=420]").give(14, "elytra");
		launch(t, 5000.5, 200, 0.5);
		t.waitTicks(30);
		t.flagsFromHere();
		t.module(SWAP).disable();
		t.module(FLY).set("type", "altitude").enable();
		t.waitTicks(60);
		t.step("status");
		t.module(FLY).disable();
		t.module(SWAP).enable();
		double y = t.player().serverPosition().y;
		int worn = damage(t, 38), spare = damage(t, 14);
		// The fresh one wears a point a second for the rest of the flight.
		String detail = String.format(Locale.ROOT, "worn elytra damage %d, put away %d, y=%.0f", worn, spare, y);
		t.check(0 <= worn && worn <= 3 && 420 <= spare && spare <= 422 && y > 20, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void altitude(TestContext t) {
		t.player().give(38, "elytra");
		launch(t, 1000.5, 130, 0.5);
		t.waitTicks(30);
		t.flagsFromHere();
		double x0 = t.player().serverPosition().x;
		t.module(FLY).set("type", "altitude").enable();
		t.waitTicks(200);
		t.module(FLY).disable();
		Vec3 p = t.player().serverPosition();
		int wear = t.player().damage(38);
		String detail = String.format(Locale.ROOT, "%.0f blocks in 10 s, y=%.0f, elytra damage %d", p.x - x0, p.y, wear);
		t.check(p.x - x0 > 60 && p.y > 50, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 3600)
	public void wearingOut(TestContext t) {
		// Altitude with an elytra that won't last the flight (102 uses left, 225 blocks up, no chestplate for Elytra Swap):
		// it has to come down and land before it breaks, not fall when it does.
		t.player().give(38, "elytra[damage=330]");
		t.module(FLY).set("after_landing", "stay");
		t.player().look(-90, 0);
		launch(t, 6000.5, 200, 0.5);
		t.module(FLY).set("type", "altitude").enable();
		t.waitTicks(1400);
		t.module(FLY).disable();
		Vec3 p = t.player().serverPosition();
		float hp = t.player().health();
		String wear = t.player().item(38).isEmpty() ? "?" : String.valueOf(t.player().damage(38));
		String detail = String.format(Locale.ROOT, "landed at y=%.0f after %.0f blocks, health %.0f, elytra damage %s of 432", p.y, p.x - 6000, hp,
			wear);
		t.check(hp >= 20 && p.x > 6000, detail);
		t.note(detail);
	}

	// Flaky in the old suite: flags Simulation now and then with three clients running (load); passes alone.
	@Test(tags = "elytra", timeoutTicks = 2400)
	public void spare(TestContext t) {
		// Altitude with Elytra Swap: the worn elytra (52 uses left, less than landing from 200 blocks over the arena
		// takes) is swapped in mid-air for the spare in the inventory, and the flight goes on.
		t.player().give(38, "elytra[damage=380]").give(14, "elytra").give(1, "diamond_chestplate");
		t.module(SWAP).enable();
		t.module(FLY).set("after_landing", "stay");
		t.player().look(-90, 0);
		launch(t, 10000.5, 200, 0.5);
		t.waitTicks(30);
		t.flagsFromHere();
		t.module(FLY).set("type", "altitude").enable();
		t.waitTicks(300);
		Vec3 p = t.player().serverPosition();
		// The elytra in use: worn, or in the hotbar during Elytra Swap's break.
		int damage = elytraDamage(t);
		String detail = String.format(Locale.ROOT, "worn elytra damage %d after %.0f blocks, y=%.0f", damage, p.x - 10000, p.y);
		t.check(damage < 100 && p.y > 50, detail);
		t.note(detail);
	}

	// Flaky in the old suite: fails about half the time on any version (elytra wore -2/-3: the swap and the reading race).
	@Test(tags = "elytra", timeoutTicks = 2400)
	public void noDurability(TestContext t) {
		// Altitude with Elytra Swap: the server glides a tick at a time, each start once it has the elytra on. 30 s.
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		launch(t, 12000.5, 200, 0.5);
		t.waitTicks(30);
		t.flagsFromHere();
		t.module(SWAP).enable();
		t.module(FLY).set("after_landing", "stay");
		t.player().look(-90, 0);
		// A teleport keeps a glide going: what it wore before Altitude was on doesn't count.
		int before = elytraDamage(t);
		t.module(FLY).set("type", "altitude").enable();
		t.waitTicks(600);
		int damage = elytraDamage(t) - before;
		float hp = t.player().health();
		Vec3 p = t.player().serverPosition();
		String detail = String.format(Locale.ROOT, "elytra wore %d in 30 s, %.0f blocks, y=%.0f, health %.0f", damage, p.x - 12000, p.y, hp);
		t.check(0 <= damage && damage <= 1 && hp > 0 && p.y > 80, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void lockHeading(TestContext t) {
		// Rotation Lock with Elytra Swap: the camera turns 90° (and back and forth) mid-flight, the flight keeps going
		// east, through Elytra Swap's glide restarts too.
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		launch(t, 14000.5, 200, 0.5);
		t.waitTicks(30);
		t.flagsFromHere();
		t.module(SWAP).enable();
		t.module(FLY).set("after_landing", "stay");
		t.module("myriad-essentials:rotation_lock").enable();
		t.player().look(-90, 0);
		t.module(FLY).set("type", "altitude").enable();
		t.waitTicks(40);
		t.player().look(0, 0);
		t.waitTicks(120);
		t.player().look(60, 30);
		t.waitTicks(120);
		t.player().look(0, -20);
		t.waitTicks(120);
		Vec3 p = t.player().serverPosition();
		String detail = String.format(Locale.ROOT, "%.0f blocks east, %.1f sideways, y=%.0f", p.x - 14000, p.z, p.y);
		t.check(p.x - 14000 > 150 && Math.abs(p.z) < 5 && p.y > 80, detail);
		t.note(detail);
	}
}
