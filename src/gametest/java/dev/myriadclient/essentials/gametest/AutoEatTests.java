package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

/** Auto Eat: eating when hungry, and the right food for low health and for hunger. */
public final class AutoEatTests extends EssentialsSuite {
	/**
	 * Hunger in short strong bursts until the food bar is at {@code level} or lower, twenty bursts at most (a very high
	 * amplifier overflows into healing).
	 */
	private static void starve(TestContext t, int level) {
		String name = t.player().name();
		for (int i = 0; i < 20; i++) {
			int food = t.player().food();
			if (food >= 0 && food <= level) break;
			t.server().run("effect give " + name + " hunger 1 100 true");
			t.waitTicks(24);
		}
	}

	@Test(tags = "inventory")
	public void autoEat(TestContext t) {
		t.player().give(8, "cooked_beef", 16);
		// The saturation every test starts with would undo the hunger.
		t.server().run("effect clear " + t.player().name());
		starve(t, 5);
		int before = t.player().food();
		t.module("myriad-essentials:auto_eat").enable();
		t.waitTicks(120);
		t.module("myriad-essentials:auto_eat").disable();
		int left = t.player().count(8), after = t.player().food();
		String detail = "food " + before + " -> " + after + ", " + (16 - left) + " eaten";
		t.check(left < 16 && after > before, detail);
		t.note(detail);
	}

	@Test(tags = "inventory", timeoutTicks = 2000)
	public void choice(TestContext t) {
		// Low health: the enchanted golden apple, even from the main inventory, over a golden apple and golden carrots.
		// Hunger: golden carrots from the main inventory, keeping the golden apples.
		t.player().give(0, "golden_carrot", 8).give(1, "golden_apple", 8).give(20, "enchanted_golden_apple", 4);
		t.server().run("damage " + t.player().name() + " 12");
		t.module("myriad-essentials:auto_eat").set("health", "true").set("health_threshold", "10").enable();
		t.waitTicks(80);
		t.module("myriad-essentials:auto_eat").disable();
		t.waitTicks(30);
		int egap = t.player().count(20), apples = t.player().count(1), carrots = t.player().count(0);
		boolean healthOk = egap == 3 && apples == 8 && carrots == 8;

		// Afresh for the second half, as the runner starts a test (Auto Eat off, its settings kept).
		t.player().fresh().at(0.5, 0, 0.5);
		t.player().give(1, "golden_apple", 8).give(19, "golden_carrot", 8);
		t.server().run("effect clear " + t.player().name());
		starve(t, 12);
		t.module("myriad-essentials:auto_eat").enable();
		t.waitTicks(80);
		t.module("myriad-essentials:auto_eat").disable();
		t.waitTicks(30);
		int carrotsLeft = t.player().count(19), applesLeft = t.player().count(1);
		boolean hungerOk = carrotsLeft < 8 && applesLeft == 8;

		String detail = "low health ate " + (4 - egap) + " enchanted from the inventory, " + (8 - apples) + " golden apples, " + (8 - carrots)
			+ " carrots; hungry ate " + (8 - carrotsLeft) + " carrots from the inventory, " + (8 - applesLeft) + " golden apples";
		t.check(healthOk && hungerOk, detail);
		t.note(detail);
	}
}
