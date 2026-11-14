package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafting by hand, as you would: the output slot has to show what the grid makes after each way of filling it (the
 * server works the result out and sends it; the client can't), with a stack still on the cursor, after taking a result,
 * after shift-clicking it, and after Inventory Tweaks' Middle Balance. Each check is made a few ticks after the click
 * and again a second later.
 */
public final class CraftingTests extends EssentialsSuite {
	/** The table's menu: the result is slot 0, the grid 1 to 9 (row by row), your inventory's first row from 10. */
	private static void table(TestContext t) {
		t.arena().set(2, 0, 0, "crafting_table");
		t.player().at(0.5, 0, 0.5, -90, 25);
	}

	@Test(tags = "crafting")
	public void whileHoldingTheStack(TestContext t) {
		// Pick up planks and right-click two into a column, the rest still on the cursor: sticks show.
		table(t);
		t.player().give(9, "oak_planks", 8);
		SortScenes.script(t, "use", "wait 10", "cursor slot 10", "mouse 0 click", "cursor slot 1", "mouse 1 click", "cursor slot 4", "mouse 1 click");
		List<String> seen = new ArrayList<>();
		boolean ok = shows(t, "stick x4", seen);
		SortScenes.script(t, "cursor slot 10", "mouse 0 click");
		ok &= shows(t, "stick x4", seen);
		done(t, ok, seen);
	}

	@Test(tags = "crafting")
	public void oneByOne(TestContext t) {
		// Nine iron put in one at a time from a stack held on the cursor: the block shows once the ninth is in.
		table(t);
		t.player().give(9, "iron_ingot", 12);
		List<String> steps = new ArrayList<>(List.of("use", "wait 10", "cursor slot 10", "mouse 0 click"));
		for (int i = 1; i <= 9; i++) steps.addAll(List.of("cursor slot " + i, "mouse 1 click"));
		SortScenes.script(t, steps.toArray(String[]::new));
		List<String> seen = new ArrayList<>();
		done(t, shows(t, "iron_block x1", seen), seen);
	}

	@Test(tags = "crafting")
	public void takeAndCraftAgain(TestContext t) {
		// Two planks in each slot of a column: take the sticks, and the next four show from what's left.
		table(t);
		t.player().give(9, "oak_planks", 4);
		SortScenes.script(t, "use", "wait 10", "cursor slot 10", "mouse 0 click",
			"cursor slot 1", "mouse 1 click", "mouse 1 click", "cursor slot 4", "mouse 1 click", "mouse 1 click");
		List<String> seen = new ArrayList<>();
		boolean ok = shows(t, "stick x4", seen);
		// Take them onto the cursor, then once more (they stack): the grid is empty after.
		SortScenes.script(t, "cursor slot 0", "mouse 0 click");
		ok &= shows(t, "stick x4", seen);
		SortScenes.script(t, "cursor slot 0", "mouse 0 click");
		ok &= shows(t, "", seen);
		done(t, ok, seen);
	}

	@Test(tags = "crafting")
	public void shiftClickResult(TestContext t) {
		// A full column of planks shift-clicked out: everything crafted, the output empty after.
		table(t);
		t.player().give(9, "oak_planks", 64).give(10, "oak_planks", 64);
		SortScenes.script(t, "use", "wait 10", "cursor slot 10", "mouse 0 click", "cursor slot 1", "mouse 0 click",
			"cursor slot 11", "mouse 0 click", "cursor slot 4", "mouse 0 click");
		List<String> seen = new ArrayList<>();
		boolean ok = shows(t, "stick x4", seen);
		SortScenes.script(t, "cursor slot 0", "mouse 0 click shift");
		ok &= shows(t, "", seen);
		done(t, ok, seen);
	}

	@Test(tags = "crafting")
	public void inventoryGrid(TestContext t) {
		// Your own 2x2 grid: a log from a held stack makes planks, with the rest of the logs still held.
		t.player().give(9, "oak_log", 5);
		SortScenes.script(t, "key e", "wait 10", "cursor slot 9", "mouse 0 click", "cursor slot 1", "mouse 1 click");
		List<String> seen = new ArrayList<>();
		boolean ok = shows(t, "oak_planks x4", seen);
		SortScenes.script(t, "cursor slot 9", "mouse 0 click");
		ok &= shows(t, "oak_planks x4", seen);
		done(t, ok, seen);
	}

	@Test(tags = "crafting")
	public void afterMiddleBalance(TestContext t) {
		// Eight single iron and a stack of 64: balanced by a middle-click, the block shows.
		table(t);
		t.player().give(9, "iron_ingot", 64).give(10, "iron_ingot", 8);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_balance", "true").enable();
		List<String> steps = new ArrayList<>(List.of("use", "wait 10", "cursor slot 11", "mouse 0 click"));
		for (int i = 1; i <= 8; i++) steps.addAll(List.of("cursor slot " + i, "mouse 1 click"));
		steps.addAll(List.of("cursor slot 10", "mouse 0 click", "cursor slot 9", "mouse 0 click", "cursor slot 5", "mouse 2 click"));
		SortScenes.script(t, steps.toArray(String[]::new));
		List<String> seen = new ArrayList<>();
		done(t, shows(t, "iron_block x1", seen), seen);
	}

	/**
	 * Whether the output slot shows {@code want} ("" for nothing) 4 ticks after the last click and again 20 ticks after
	 * that, each noted in {@code seen}.
	 */
	private static boolean shows(TestContext t, String want, List<String> seen) {
		boolean ok = true;
		for (int wait : new int[] {4, 20}) {
			t.waitTicks(wait);
			String shown = t.onGame(() -> {
				ItemStack s = Minecraft.getInstance().player.containerMenu.getSlot(0).getItem();
				return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + " x" + s.getCount();
			});
			boolean right = shown.equals(want);
			seen.add((shown.isEmpty() ? "nothing" : shown) + (right ? "" : " (wanted " + (want.isEmpty() ? "nothing" : want) + ")"));
			ok &= right;
		}
		return ok;
	}

	private static void done(TestContext t, boolean ok, List<String> seen) {
		String detail = "output showed " + String.join(", ", seen);
		t.check(ok, detail);
		t.note(detail);
	}
}
