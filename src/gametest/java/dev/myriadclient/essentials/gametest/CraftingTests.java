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
		// The grid empty and every stick in the inventory, on screen too (the game predicts one craft; the server makes 64).
		for (int wait : new int[] {4, 20}) {
			t.waitTicks(wait);
			String screen = t.onGame(() -> {
				var menu = Minecraft.getInstance().player.containerMenu;
				int grid = 0, sticks = 0;
				for (int i = 1; i <= 9; i++) grid += menu.getSlot(i).getItem().getCount();
				for (int i = 10; i < menu.slots.size(); i++) if (menu.getSlot(i).getItem().is(net.minecraft.world.item.Items.STICK)) sticks += menu.getSlot(i).getItem().getCount();
				return grid + " in the grid, " + sticks + " sticks";
			});
			boolean right = screen.equals("0 in the grid, 256 sticks");
			seen.add(screen + (right ? "" : " (wanted 0 in the grid, 256 sticks)"));
			ok &= right;
		}
		done(t, ok, seen);
	}

	@Test(tags = "crafting")
	public void balanceWithinClickBudget(TestContext t) {
		// A stack of 64 and one to eight in the other slots: evening that out by hand takes about 85 clicks, more than 2b2t
		// takes in 4 s. The balance stops at the budget (72 in 5 s) and says so; a middle-click later finishes it.
		// Cobblestone: nine of it make nothing, so the recipe book can't fill the grid instead.
		table(t);
		t.player().give(9, "cobblestone", 64);
		for (int i = 1; i <= 8; i++) t.player().give(9 + i, "cobblestone", i);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_balance", "true").enable();
		List<String> steps = new ArrayList<>(List.of("use", "wait 10"));
		// Each stack into its own grid slot (shift-clicks would merge them): picked up and put down whole.
		for (int i = 0; i <= 8; i++) steps.addAll(List.of("cursor slot " + (10 + i), "mouse 0 click", "cursor slot " + (1 + i), "mouse 0 click"));
		SortScenes.script(t, steps.toArray(String[]::new));
		t.waitTicks(110);
		SortScenes.script(t, "cursor slot 5", "mouse 2 click");
		t.waitTicks(2);
		int used = t.onGame(() -> dev.myriadclient.api.Myriad.limits().used(dev.myriadclient.api.service.PacketLimits.Kind.INVENTORY));
		boolean warned = t.chat().stream().anyMatch(l -> l.contains("click limit"));
		t.waitTicks(110);
		SortScenes.script(t, "cursor slot 5", "mouse 2 click");
		t.waitTicks(20);
		String counts = t.onGame(() -> {
			var menu = Minecraft.getInstance().player.containerMenu;
			List<String> c = new ArrayList<>();
			for (int i = 1; i <= 9; i++) c.add(String.valueOf(menu.getSlot(i).getItem().getCount()));
			return String.join(",", c) + (menu.getCarried().isEmpty() ? "" : " cursor " + menu.getCarried().getCount());
		});
		String detail = used + " clicks in the window after the first balance" + (warned ? ", warned" : "") + "; grid after the second: " + counts;
		t.check(used <= 72 && counts.equals("12,11,11,11,11,11,11,11,11"), detail);
		t.note(detail);
	}

	@Test(tags = "crafting")
	public void balanceFromTheBook(TestContext t) {
		// One iron in eight grid slots and the other 64 in the ninth, none left in the inventory: the grid makes a block
		// from the book, so a middle-click fills it through the book (the 64 shift-clicked out, then one placement): 8 in each
		// slot, two clicks at most (that one and core's check of the slots the server changed).
		t.server().run("recipe give " + t.player().name() + " *");
		table(t);
		t.player().give(9, "iron_ingot", 64).give(10, "iron_ingot", 8);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_balance", "true").enable();
		List<String> steps = new ArrayList<>(List.of("use", "wait 10", "cursor slot 11", "mouse 0 click"));
		for (int i = 1; i <= 8; i++) steps.addAll(List.of("cursor slot " + i, "mouse 1 click"));
		steps.addAll(List.of("cursor slot 10", "mouse 0 click", "cursor slot 9", "mouse 0 click"));
		SortScenes.script(t, steps.toArray(String[]::new));
		t.waitTicks(110);
		int before = t.onGame(() -> dev.myriadclient.api.Myriad.limits().used(dev.myriadclient.api.service.PacketLimits.Kind.INVENTORY));
		SortScenes.script(t, "cursor slot 5", "mouse 2 click");
		t.waitTicks(20);
		int used = t.onGame(() -> dev.myriadclient.api.Myriad.limits().used(dev.myriadclient.api.service.PacketLimits.Kind.INVENTORY)) - before;
		String counts = t.onGame(() -> {
			var menu = Minecraft.getInstance().player.containerMenu;
			List<String> c = new ArrayList<>();
			for (int i = 1; i <= 9; i++) c.add(String.valueOf(menu.getSlot(i).getItem().getCount()));
			int rest = 0;
			for (int i = 10; i < menu.slots.size(); i++) if (menu.getSlot(i).getItem().is(net.minecraft.world.item.Items.IRON_INGOT)) rest += menu.getSlot(i).getItem().getCount();
			return String.join(",", c) + ", " + rest + " left";
		});
		String detail = "grid " + counts + ", " + used + " clicks";
		t.check(counts.equals("8,8,8,8,8,8,8,8,8, 0 left") && used <= 2, detail);
		t.note(detail);
	}

	@Test(tags = "crafting")
	public void balanceFromTheBookWithMore(TestContext t) {
		// 20 iron in one grid slot and one in each of the others, five stacks more in the inventory: plain placements from
		// the book even it out with the grid's own 28 (3 a slot, the 1 left over to the inventory), no clicks of its own.
		t.server().run("recipe give " + t.player().name() + " *");
		table(t);
		t.player().give(9, "iron_ingot", 28);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_balance", "true").enable();
		List<String> steps = new ArrayList<>(List.of("use", "wait 10", "cursor slot 10", "mouse 0 click"));
		for (int i = 1; i <= 8; i++) steps.addAll(List.of("cursor slot " + i, "mouse 1 click"));
		steps.addAll(List.of("cursor slot 9", "mouse 0 click"));
		SortScenes.script(t, steps.toArray(String[]::new));
		for (int i = 0; i < 5; i++) t.player().give(20 + i, "iron_ingot", 64);
		t.waitTicks(110);
		int before = t.onGame(() -> dev.myriadclient.api.Myriad.limits().used(dev.myriadclient.api.service.PacketLimits.Kind.INVENTORY));
		SortScenes.script(t, "cursor slot 5", "mouse 2 click");
		t.waitTicks(20);
		int used = t.onGame(() -> dev.myriadclient.api.Myriad.limits().used(dev.myriadclient.api.service.PacketLimits.Kind.INVENTORY)) - before;
		String counts = t.onGame(() -> {
			var menu = Minecraft.getInstance().player.containerMenu;
			List<String> c = new ArrayList<>();
			for (int i = 1; i <= 9; i++) c.add(String.valueOf(menu.getSlot(i).getItem().getCount()));
			int rest = 0;
			for (int i = 10; i < menu.slots.size(); i++) if (menu.getSlot(i).getItem().is(net.minecraft.world.item.Items.IRON_INGOT)) rest += menu.getSlot(i).getItem().getCount();
			return String.join(",", c) + ", " + rest + " in the inventory";
		});
		String detail = "grid " + counts + ", " + used + " clicks";
		t.check(counts.equals("3,3,3,3,3,3,3,3,3, 321 in the inventory") && used <= 1, detail);
		t.note(detail);
	}

	@Test(tags = "crafting")
	public void balanceMovesToTheBooksPlace(TestContext t) {
		// Glass panes laid out in the bottom two rows: the book evens them out in the top two, where it puts them (the
		// user chose fewer clicks over keeping the layout). 25 glass: 20 in the first slot of the middle row, one in each
		// of the other five; 4 in each of the top six after, the 1 left over in the inventory.
		t.server().run("recipe give " + t.player().name() + " *");
		table(t);
		t.player().give(9, "glass", 25);
		t.module(SortScenes.INVENTORY_TWEAKS).set("middle_balance", "true").enable();
		List<String> steps = new ArrayList<>(List.of("use", "wait 10", "cursor slot 10", "mouse 0 click"));
		for (int i = 5; i <= 9; i++) steps.addAll(List.of("cursor slot " + i, "mouse 1 click"));
		steps.addAll(List.of("cursor slot 4", "mouse 0 click", "wait 5", "cursor slot 5", "mouse 2 click"));
		SortScenes.script(t, steps.toArray(String[]::new));
		t.waitTicks(20);
		String counts = t.onGame(() -> {
			var menu = Minecraft.getInstance().player.containerMenu;
			List<String> c = new ArrayList<>();
			for (int i = 1; i <= 9; i++) c.add(String.valueOf(menu.getSlot(i).getItem().getCount()));
			return String.join(",", c);
		});
		String detail = "grid " + counts;
		t.check(counts.equals("4,4,4,4,4,4,0,0,0"), detail);
		t.note(detail);
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
