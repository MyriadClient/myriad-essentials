package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Armor: a carried set put on standing and at a sprint, worn-out pieces swapped or taken off (Preserve), and a worn
 * elytra kept on rather than swapped for every fresher one.
 */
public final class AutoArmorTests extends EssentialsSuite {
	private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};

	/** What the armour slots hold: whether every one is a diamond piece, and the detail line naming them feet to head. */
	private record Worn(boolean allDiamond, String detail) {
	}

	/** A diamond set in the inventory's first four slots, helmet first. */
	private static void carrySet(TestContext t) {
		for (int i = 0; i < PIECES.length; i++) t.player().give(9 + i, "diamond_" + PIECES[i]);
	}

	private static Worn worn(TestContext t) {
		List<String> names = new ArrayList<>();
		boolean all = true;
		for (int slot = 36; slot <= 39; slot++) {
			String w = t.player().item(slot);
			if (!w.startsWith("minecraft:diamond_")) all = false;
			names.add(w.isEmpty() ? "-" : w.replace("minecraft:", ""));
		}
		return new Worn(all, "worn: " + String.join(", ", names));
	}

	/** The damage on the item in {@code slot}; -1 if the slot is empty, so a missing spare doesn't read as a fresh one. */
	private static int damage(TestContext t, int slot) {
		return t.player().item(slot).isEmpty() ? -1 : t.player().damage(slot);
	}

	@Test(tags = "inventory")
	public void autoArmor(TestContext t) {
		carrySet(t);
		t.module("myriad-essentials:auto_armor").enable();
		t.waitTicks(40);
		Worn w = worn(t);
		t.check(w.allDiamond(), w.detail());
		t.note(w.detail());
	}

	@Test(tags = "inventory")
	public void sprinting(TestContext t) {
		carrySet(t);
		t.hold(Key.FORWARD, Key.SPRINT).forTicksThenContinue(60);
		t.waitTicks(2);
		t.module("myriad-essentials:auto_armor").enable();
		t.waitTicks(58);
		Worn w = worn(t);
		t.check(w.allDiamond(), w.detail());
		t.note(w.detail());
	}

	@Test(tags = "inventory")
	public void preserve(TestContext t) {
		// A chestplate with 8 uses left and a better one carried: swapped. Leggings with 5 left and none spare: taken off,
		// and not put back.
		t.player().give(38, "diamond_chestplate[damage=520]").give(37, "diamond_leggings[damage=490]").give(14, "netherite_chestplate");
		t.module("myriad-essentials:auto_armor").enable();
		t.waitTicks(40);
		String chest = t.player().item(38), legs = t.player().item(37);
		boolean ok = chest.equals("minecraft:netherite_chestplate") && legs.isEmpty();
		String detail = "chest " + (chest.isEmpty() ? "empty" : chest) + ", legs " + (legs.isEmpty() ? "empty" : legs);
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "inventory")
	public void keepsWornElytra(TestContext t) {
		// An elytra with a little wear on, three fresh ones carried: it stays on (used until Preserve), the spares untouched.
		// Swapping in the freshest at every point of wear used to wear all of them in turn.
		t.player().give(38, "elytra[damage=10]");
		for (int slot = 14; slot <= 16; slot++) t.player().give(slot, "elytra");
		t.module("myriad-essentials:auto_armor").set("chest", "elytra").set("await_landing", "false").enable();
		t.waitTicks(40);
		int worn = damage(t, 38);
		List<Integer> spares = List.of(damage(t, 14), damage(t, 15), damage(t, 16));
		boolean ok = worn == 10 && spares.equals(List.of(0, 0, 0));
		String detail = "worn elytra damage " + worn + ", spares " + spares;
		t.check(ok, detail);
		t.note(detail);
	}
}
