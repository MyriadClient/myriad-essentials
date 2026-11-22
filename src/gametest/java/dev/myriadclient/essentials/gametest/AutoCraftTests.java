package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/**
 * Auto Craft: as many of the listed items as the inventory has the ingredients for, small recipes in the inventory's
 * grid, larger ones at a crafting table in reach (closed again after), each batch the whole grid at once.
 */
public final class AutoCraftTests extends EssentialsSuite {
	private static final String CRAFT = "myriad-essentials:auto_craft";

	private static int count(TestContext t, String item) {
		int n = 0;
		for (String s : t.player().inventory()) {
			if (s.startsWith(item + " x")) n += Integer.parseInt(s.substring(item.length() + 2));
			else if (s.equals(item)) n += 1;
		}
		return n;
	}

	private static long now(TestContext t) {
		return t.onGame(() -> (long) dev.myriadclient.api.util.Ticks.current());
	}

	private static boolean closed(TestContext t) {
		return t.onGame(() -> net.minecraft.client.Minecraft.getInstance().player.containerMenu
			== net.minecraft.client.Minecraft.getInstance().player.inventoryMenu);
	}

	@Test(name = "inventory_grid", tags = "inventory", timeoutTicks = 400)
	public void inventoryGrid(TestContext t) {
		t.server().run("recipe give " + t.player().name() + " *");
		t.player().give(9, "oak_log", 16);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:oak_planks");
		t.waitTicks(10);
		t.flagsFromHere();
		long start = now(t);
		t.module(CRAFT).enable();
		int planks = 0;
		for (int i = 0; i < 40 && planks < 64; i++) {
			t.waitTicks(2);
			planks = count(t, "oak_planks");
		}
		long took = now(t) - start;
		t.module(CRAFT).disable();
		int logs = count(t, "oak_log");
		String detail = String.format(Locale.ROOT, "%d planks from 16 logs (%d logs left) in %d ticks, no table", planks, logs, took);
		t.check(planks == 64 && logs == 0, detail);
		t.note(detail);
	}

	@Test(name = "many_batches", tags = "inventory", timeoutTicks = 400)
	public void manyBatches(TestContext t) {
		// Four stacks of logs: four batches, all in one go (1024 planks, sixteen stacks).
		t.server().run("recipe give " + t.player().name() + " *");
		for (int i = 9; i < 13; i++) t.player().give(i, "oak_log", 64);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:oak_planks");
		t.waitTicks(10);
		t.flagsFromHere();
		long start = now(t);
		t.module(CRAFT).enable();
		int planks = 0;
		for (int i = 0; i < 40 && planks < 1024; i++) {
			t.waitTicks(2);
			planks = count(t, "oak_planks");
		}
		long took = now(t) - start;
		t.module(CRAFT).disable();
		int logs = count(t, "oak_log");
		String detail = String.format(Locale.ROOT, "%d planks from 256 logs (%d left) in %d ticks", planks, logs, took);
		t.check(planks == 1024 && logs == 0, detail);
		t.note(detail);
	}

	@Test(name = "full_inventory", tags = "inventory", timeoutTicks = 400)
	public void fullInventory(TestContext t) {
		// 34 stacks of dirt and a stack of logs: room for 16 logs' planks (a stack) beside what's left, no more, nothing dropped.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		for (int i = 0; i < 36; i++) if (i != 9) t.player().give(i, "dirt", 64);
		t.server().run("item replace entity " + t.player().name() + " container.10 with air");
		t.player().give(9, "oak_log", 64);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:oak_planks");
		t.waitTicks(10);
		t.flagsFromHere();
		t.module(CRAFT).enable();
		t.waitTicks(40);
		t.module(CRAFT).disable();
		int planks = count(t, "oak_planks"), logs = count(t, "oak_log");
		boolean dropped = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..10]");
		String detail = String.format(Locale.ROOT, "%d planks, %d logs left, %s", planks, logs, dropped ? "items on the ground" : "nothing dropped");
		t.check(planks == 64 && logs == 48 && !dropped, detail);
		t.note(detail);
	}

	@Test(name = "drop", tags = "inventory", timeoutTicks = 400)
	public void drop(TestContext t) {
		// Drop: crafted and thrown out at once, nothing kept; the planks lie on the ground.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		t.player().at(0.5, 0, 0.5, -90, 60);
		t.player().give(9, "oak_log", 16);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:oak_planks").set("drop", "true");
		t.waitTicks(10);
		t.flagsFromHere();
		t.module(CRAFT).enable();
		int logs = 16;
		for (int i = 0; i < 40 && logs > 0; i++) {
			t.waitTicks(2);
			logs = count(t, "oak_log");
		}
		t.waitTicks(10);
		t.module(CRAFT).disable();
		t.module(CRAFT).set("drop", "false");
		int kept = count(t, "oak_planks");
		boolean thrown = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..10,nbt={Item:{id:\"minecraft:oak_planks\"}}]");
		String detail = String.format(Locale.ROOT, "%d logs left, %d planks kept, planks %s", logs, kept, thrown ? "on the ground" : "nowhere");
		t.check(logs == 0 && kept == 0 && thrown, detail);
		t.note(detail);
	}

	@Test(name = "chests_from_logs", tags = "inventory", timeoutTicks = 600)
	public void chestsFromLogs(TestContext t) {
		// Chests wanted, only logs to hand: planked on the way, all of it (16 logs, 64 planks, 8 chests), at the table.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		t.arena().set(2, 0, 0, "crafting_table");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.player().give(9, "oak_log", 16);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:chest");
		t.waitTicks(10);
		t.flagsFromHere();
		t.module(CRAFT).enable();
		int chests = 0;
		for (int i = 0; i < 60 && chests < 8; i++) {
			t.waitTicks(2);
			chests = count(t, "chest");
		}
		t.waitTicks(20);
		t.module(CRAFT).disable();
		int logs = count(t, "oak_log"), planks = count(t, "oak_planks");
		boolean dropped = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..10]");
		String detail = String.format(Locale.ROOT, "%d chests from 16 logs (%d logs, %d planks left), table %s%s", chests, logs, planks, closed(t) ? "closed" : "still open",
			dropped ? ", items on the ground" : "");
		t.check(chests == 8 && logs == 0 && planks == 0 && closed(t) && !dropped, detail);
		t.note(detail);
	}

	@Test(name = "golden_carrots", tags = "inventory", timeoutTicks = 600)
	public void goldenCarrots(TestContext t) {
		// Golden carrots from a gold block and five carrots: the block into ingots, five ingots into nuggets, no more.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		t.arena().set(2, 0, 0, "crafting_table");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.player().give(9, "gold_block", 2).give(10, "carrot", 5);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:golden_carrot");
		t.waitTicks(10);
		t.flagsFromHere();
		t.module(CRAFT).enable();
		int carrots = 0;
		for (int i = 0; i < 60 && carrots < 5; i++) {
			t.waitTicks(2);
			carrots = count(t, "golden_carrot");
		}
		t.waitTicks(20);
		t.module(CRAFT).disable();
		int blocks = count(t, "gold_block"), ingots = count(t, "gold_ingot"), nuggets = count(t, "gold_nugget"), raw = count(t, "carrot");
		boolean dropped = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..10]");
		String detail = String.format(Locale.ROOT, "%d golden carrots; left: %d gold blocks, %d ingots, %d nuggets, %d carrots%s", carrots, blocks, ingots, nuggets, raw,
			dropped ? "; items on the ground" : "");
		// One block of the two opened (nine ingots), five of those into 45 nuggets, 40 used: the other block untouched.
		t.check(carrots == 5 && raw == 0 && blocks == 1 && ingots == 4 && nuggets == 5 && !dropped, detail);
		t.note(detail);
	}

	@Test(name = "table", tags = "inventory", timeoutTicks = 600)
	public void table(TestContext t) {
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		t.arena().set(2, 0, 0, "crafting_table");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.player().give(9, "iron_ingot", 64).give(10, "iron_ingot", 17);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:iron_block");
		t.waitTicks(10);
		t.flagsFromHere();
		long start = now(t);
		t.module(CRAFT).enable();
		int blocks = 0;
		for (int i = 0; i < 60 && blocks < 9; i++) {
			t.waitTicks(2);
			blocks = count(t, "iron_block");
		}
		long took = now(t) - start;
		t.waitTicks(20);
		t.module(CRAFT).disable();
		int ingots = count(t, "iron_ingot");
		boolean shut = closed(t);
		String detail = String.format(Locale.ROOT, "%d iron blocks from 81 ingots (%d left) in %d ticks, table %s", blocks, ingots, took, shut ? "closed" : "still open");
		t.check(blocks == 9 && ingots == 0 && shut, detail);
		t.note(detail);
	}
}
