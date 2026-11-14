package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/** Replenish: a low hotbar stack topped up from the inventory, standing, walking and at a sprint. */
public final class ReplenishTests extends EssentialsSuite {
	/** One cobblestone in the first hotbar slot, a full stack in the inventory to top it up from. */
	private static void lowStack(TestContext t) {
		t.player().give(0, "cobblestone", 1).give(9, "cobblestone", 64);
	}

	// Flaky in the old suite: the first replenish run right after the clients started read the hotbar before it was synced.
	@Test(tags = "inventory")
	public void replenish(TestContext t) {
		lowStack(t);
		t.module("myriad-essentials:replenish").enable();
		t.waitTicks(30);
		int n = t.player().count(0);
		t.check(n > 1, "hotbar stack now " + n);
		t.note("hotbar stack now " + n);
	}

	// Flaky in the old suite: as replenish, and flagged Simulation now and then with three clients running.
	@Test(tags = "inventory")
	public void walking(TestContext t) {
		lowStack(t);
		t.hold(Key.FORWARD).forTicksThenContinue(40);
		t.waitTicks(2);
		t.module("myriad-essentials:replenish").enable();
		t.waitTicks(38);
		int n = t.player().count(0);
		t.check(n > 1, "hotbar stack now " + n);
		t.note("hotbar stack now " + n);
	}

	// Flaky in the old suite: as replenish.
	@Test(tags = "inventory")
	public void sprinting(TestContext t) {
		// Two low stacks while sprinting: both topped up in one go, the click got past Grim's sprint check in the same tick
		// (stop and start around it), so the sprint never breaks.
		lowStack(t);
		t.player().give(1, "dirt", 2).give(10, "dirt", 64);
		t.hold(Key.FORWARD, Key.SPRINT).forTicksThenContinue(40);
		t.waitTicks(5);
		t.module("myriad-essentials:replenish").enable();
		t.waitTicks(35);
		double x = t.player().serverPosition().x;
		int a = t.player().count(0), b = t.player().count(1);
		// Sprinting covers about 5.6 blocks a second: 2 s of it, less the run-up.
		boolean ok = a == 64 && b == 64 && x > 10;
		String detail = String.format(Locale.ROOT, "hotbar stacks now %d and %d, ran to x=%.1f", a, b, x);
		t.check(ok, detail);
		t.note(detail);
	}
}
