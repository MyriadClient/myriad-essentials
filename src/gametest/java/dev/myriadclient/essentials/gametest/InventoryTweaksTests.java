package dev.myriadclient.essentials.gametest;

import dev.myriadclient.essentials.gametest.SortScenes.ShulkerSort;
import dev.myriadclient.essentials.gametest.SortScenes.Sort;
import dev.myriadclient.essentials.gametest.SortScenes.Stack;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Inventory Tweaks' Middle Sort: a middle-click over a chest's slots sorts the chest (clicked, or held as by hand, with
 * shulker boxes in the mix), over your own inventory's slots your main inventory, leaving the hotbar as it was. Middle
 * Balance: over a crafting grid (a table's, your own) it shares each item out evenly.
 */
public final class InventoryTweaksTests extends EssentialsSuite {
	@Test(tags = "inventory")
	public void middleSort(TestContext t) {
		SortScenes.sortPass(t, Sort.MIDDLE);
	}

	@Test(tags = "inventory")
	public void middleSortHeld(TestContext t) {
		SortScenes.sortPass(t, Sort.MIDDLE.openedByHand());
	}

	@Test(tags = "inventory", timeoutTicks = 2000)
	public void middleSortShulkers(TestContext t) {
		SortScenes.shulkerSortPass(t, ShulkerSort.MIDDLE);
	}

	@Test(tags = "inventory", timeoutTicks = 2000)
	public void middleSortInventoryShulkers(TestContext t) {
		SortScenes.shulkerSortPass(t, ShulkerSort.INVENTORY);
	}

	@Test(tags = "inventory")
	public void middleSortInventory(TestContext t) {
		// Inventory Tweaks' middle-click over your own inventory in its screen: the main inventory sorted, the hotbar as it was.
		Random rng = new Random(11);
		List<String> hotbar = List.of("diamond_sword", "", "bread", "", "", "torch", "", "", "");
		List<String> names = List.copyOf(SortScenes.SORT_ITEMS.keySet());
		Map<String, Integer> totals = new TreeMap<>();
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < 27; i++) slots.add(i);
		Collections.shuffle(slots, rng);
		for (int i = 0; i < hotbar.size(); i++) if (!hotbar.get(i).isEmpty()) t.player().give(i, hotbar.get(i));
		for (int slot : slots.subList(0, 18)) {
			String item = names.get(rng.nextInt(names.size()));
			int n = 1 + rng.nextInt(SortScenes.SORT_ITEMS.get(item) / 2);
			t.player().give(9 + slot, item, n);
			totals.merge(item, n, Integer::sum);
		}
		t.waitTicks(20);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_sort", "true").enable();
		SortScenes.script(t, "key e", "wait 10", "cursor slot 20", "mouse 2 click", "wait 200", "status", "key escape", "wait 5");
		Map<Integer, Stack> items = SortScenes.inventorySlots(t);
		List<Stack> main = SortScenes.inventoryRange(items);
		boolean sorted = SortScenes.sortedAndMerged(main);
		Map<String, Integer> now = new TreeMap<>();
		for (Stack s : main) if (s != null) now.merge(s.item(), s.count(), Integer::sum);
		List<String> hotbarNow = new ArrayList<>();
		for (int i = 0; i < 9; i++) hotbarNow.add(items.containsKey(i) ? items.get(i).item() : "");
		boolean kept = now.equals(totals), hotbarKept = hotbarNow.equals(hotbar);
		String detail = "main inventory " + (sorted ? "sorted" : "not sorted") + ", " + (kept ? "nothing lost" : "items changed: " + now + " vs " + totals)
			+ ", hotbar " + (hotbarKept ? "as it was" : hotbarNow);
		t.check(sorted && kept && hotbarKept, detail);
		t.note(detail);
	}

	@Test(tags = "inventory")
	public void middleBalanceCraftingTable(TestContext t) {
		// A crafting table's grid with 64 iron in one slot and one in each other: a middle-click makes nine of 8.
		t.arena().set(2, 0, 0, "crafting_table");
		t.player().give(9, "iron_ingot", 64).give(10, "iron_ingot", 8);
		t.player().at(0.5, 0, 0.5, -90, 25);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_balance", "true").enable();
		// The table's menu: the grid is slots 1 to 9, the inventory's first row 10 on.
		List<String> steps = new ArrayList<>(List.of("use", "wait 10", "cursor slot 11", "mouse 0 click"));
		for (int i = 1; i <= 8; i++) steps.addAll(List.of("cursor slot " + i, "mouse 1 click"));
		steps.addAll(List.of("cursor slot 10", "mouse 0 click", "cursor slot 9", "mouse 0 click", "wait 5", "cursor slot 5", "mouse 2 click", "wait 20"));
		SortScenes.script(t, steps.toArray(String[]::new));
		balanced(t, 1, 9, Collections.nCopies(9, "iron_ingot x8"), Map.of("iron_ingot", 72));
	}

	@Test(tags = "inventory")
	public void middleBalanceInventoryGrid(TestContext t) {
		// Your own 2x2 grid with two items: 31 and 1 iron, 9 and 1 planks.
		t.player().give(9, "iron_ingot", 32).give(10, "oak_planks", 10);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_balance", "true").enable();
		// The inventory's menu: the grid is slots 1 to 4, the main inventory 9 on.
		SortScenes.script(t, "key e", "wait 10",
			"cursor slot 9", "mouse 0 click", "cursor slot 2", "mouse 1 click", "cursor slot 1", "mouse 0 click",
			"cursor slot 10", "mouse 0 click", "cursor slot 4", "mouse 1 click", "cursor slot 3", "mouse 0 click",
			"wait 5", "cursor slot 1", "mouse 2 click", "wait 20");
		balanced(t, 1, 4, List.of("iron_ingot x16", "iron_ingot x16", "oak_planks x5", "oak_planks x5"), Map.of("iron_ingot", 32, "oak_planks", 10));
	}

	/**
	 * Checks the open menu's slots {@code from} to {@code to} hold {@code want}, then that after closing the screen (which
	 * puts the grid back in your inventory) the server has {@code totals}: nothing lost or made.
	 */
	private static void balanced(TestContext t, int from, int to, List<String> want, Map<String, Integer> totals) {
		List<String> grid = t.onGame(() -> {
			var menu = Minecraft.getInstance().player.containerMenu;
			List<String> held = new ArrayList<>();
			for (int i = from; i <= to; i++) {
				ItemStack s = menu.getSlot(i).getItem();
				held.add(s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + " x" + s.getCount());
			}
			return held;
		});
		t.step("key escape");
		t.waitTicks(10);
		Map<String, Integer> now = new TreeMap<>();
		for (String s : t.player().inventory()) {
			if (s.isEmpty()) continue;
			String[] parts = s.split(" x");
			now.merge(parts[0], Integer.parseInt(parts[1]), Integer::sum);
		}
		boolean even = grid.equals(want), kept = now.equals(new TreeMap<>(totals));
		String detail = "grid " + grid + (even ? "" : ", wanted " + want) + ", " + (kept ? "nothing lost" : "items now " + now + " vs " + totals);
		t.check(even && kept, detail);
		t.note(detail);
	}
}
