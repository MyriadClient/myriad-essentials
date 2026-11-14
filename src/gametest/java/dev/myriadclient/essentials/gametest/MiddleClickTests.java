package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Middle Click: a pearl or rocket from the main inventory with a full hotbar, put back after, standing and gliding. */
public final class MiddleClickTests extends EssentialsSuite {
	/** Every hotbar slot taken, with nothing Middle Click may use and no building blocks for a borrow to go over. */
	private static final List<String> FULL_HOTBAR = List.of("diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "bow", "shield",
		"golden_apple", "totem_of_undying", "shears");

	private static void fillHotbar(TestContext t) {
		for (int i = 0; i < FULL_HOTBAR.size(); i++) t.player().give(i, FULL_HOTBAR.get(i));
	}

	/** The hotbar's item ids on the server, without the namespace ("" for an empty slot). */
	private static List<String> hotbar(TestContext t) {
		List<String> items = new ArrayList<>();
		for (int i = 0; i < 9; i++) items.add(t.player().item(i).replace("minecraft:", ""));
		return items;
	}

	@Test(tags = "inventory")
	public void inventory(TestContext t) {
		// No pearl in the hotbar and no free slot: one comes from the inventory over another slot, and both go back after.
		// The wait before the click lets the items reach the client through any added lag.
		fillHotbar(t);
		t.player().give(14, "ender_pearl", 16);
		t.module("myriad-essentials:middle_click").set("air", "pearl").set("block", "pearl").enable();
		t.player().look(-90, -60);
		t.waitTicks(10);
		t.step("middle");
		t.waitTicks(60);
		t.module("myriad-essentials:middle_click").disable();
		int pearls = t.player().count(14);
		List<String> now = hotbar(t);
		// Thrown nearly straight up, it would land and teleport the tester in the middle of the next test.
		t.server().run("kill @e[type=ender_pearl]");
		String detail = pearls + " pearls back in the inventory, hotbar " + (now.equals(FULL_HOTBAR) ? "as it was" : now);
		t.check(pearls == 15 && now.equals(FULL_HOTBAR), detail);
		t.note(detail);
	}

	@Test(tags = "inventory")
	public void rocketFlying(TestContext t) {
		// A rocket from the inventory while gliding: there's never a still moment, so the click goes while flying.
		t.player().give(38, "elytra");
		fillHotbar(t);
		t.player().give(14, "firework_rocket", 16);
		// High in the air well away from the arena, falling while the chunks there come in.
		t.server().run("tp " + t.player().name() + " " + t.arena().at(5000.5, 200, 0.5) + " -90 0");
		t.waitTicks(30);
		t.flagsFromHere();
		double x0 = t.player().serverPosition().x;
		t.module("myriad-essentials:middle_click").set("flying", "rocket").enable();
		t.player().look(-90, -10);
		t.hold(Key.JUMP).forTicks(2);
		t.waitTicks(2);
		t.step("middle");
		t.waitTicks(100);
		t.module("myriad-essentials:middle_click").disable();
		int rockets = t.player().count(14);
		List<String> now = hotbar(t);
		double x = t.player().serverPosition().x;
		String detail = rockets + " rockets back in the inventory, hotbar " + (now.equals(FULL_HOTBAR) ? "as it was" : now)
			+ String.format(Locale.ROOT, ", %.0f blocks", x - x0);
		t.check(rockets == 15 && now.equals(FULL_HOTBAR), detail);
		t.note(detail);
	}
}
