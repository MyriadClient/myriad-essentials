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

	/** How many {@code item} the container at arena {@code x y z} holds, by the server. */
	private static int stored(TestContext t, int x, int y, int z, String item) {
		var a = t.arena();
		String out = t.server().run("execute if items block " + a.x(x) + " " + a.y(y) + " " + a.z(z) + " container.* minecraft:" + item);
		int at = out.lastIndexOf("count: ");
		return at < 0 ? 0 : Integer.parseInt(out.substring(at + 7).trim());
	}

	@Test(name = "containers", tags = "inventory", timeoutTicks = 1200)
	public void containers(TestContext t) {
		// Ender chests from two inputs, obsidian in a chest and eyes in a barrel, into an output chest: 24 of them (192
		// obsidian, 24 eyes), the inputs emptied, nothing kept or dropped.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		var a = t.arena();
		t.arena().set(2, 0, 0, "crafting_table");
		t.arena().set(2, 0, 2, "chest");
		t.arena().set(2, 0, -2, "barrel");
		t.arena().set(-1, 0, 2, "chest");
		for (int slot = 0; slot < 3; slot++)
			t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(2) + " container." + slot + " with obsidian 64");
		t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(-2) + " container.0 with ender_eye 24");
		t.player().at(0.5, 0, 0.5, -90, 20);
		String inputs = a.x(2) + " " + a.y(0) + " " + a.z(2) + "; " + a.x(2) + " " + a.y(0) + " " + a.z(-2);
		String output = a.x(-1) + " " + a.y(0) + " " + a.z(2);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:ender_chest").set("inputs", inputs).set("outputs", output);
		t.waitTicks(10);
		t.flagsFromHere();
		long start = now(t);
		t.module(CRAFT).enable();
		int made = 0, shown = 0;
		for (int i = 0; i < 100 && made < 24; i++) {
			for (int k = 0; k < 5; k++) {
				t.waitTicks(1);
				// Nothing shown: the inputs', outputs' and table's screens stay hidden.
				if (t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.screen()
					instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) shown++;
			}
			made = stored(t, -1, 0, 2, "ender_chest");
		}
		long took = now(t) - start;
		t.waitTicks(20);
		t.module(CRAFT).disable();
		t.module(CRAFT).set("inputs", "").set("outputs", "");
		int obsidian = stored(t, 2, 0, 2, "obsidian"), eyes = stored(t, 2, 0, -2, "ender_eye");
		int kept = count(t, "ender_chest") + count(t, "obsidian") + count(t, "ender_eye");
		boolean dropped = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..10]");
		String detail = String.format(Locale.ROOT, "%d ender chests in the output in %d ticks; inputs left %d obsidian, %d eyes; %d kept%s, table %s, a screen shown %d ticks",
			made, took, obsidian, eyes, kept, dropped ? ", items on the ground" : "", closed(t) ? "closed" : "still open", shown);
		t.check(made == 24 && obsidian == 0 && eyes == 0 && kept == 0 && !dropped && closed(t) && shown == 0, detail);
		t.note(detail);
	}

	/** The containers test's setup: 192 obsidian in a chest and 24 eyes in a barrel as inputs, an empty chest as the output, Auto Craft set to make ender chests. */
	private static void enderChestLine(TestContext t) {
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		var a = t.arena();
		a.set(2, 0, 0, "crafting_table");
		a.set(2, 0, 2, "chest");
		a.set(2, 0, -2, "barrel");
		a.set(-1, 0, 2, "chest");
		for (int slot = 0; slot < 3; slot++)
			t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(2) + " container." + slot + " with obsidian 64");
		t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(-2) + " container.0 with ender_eye 24");
		t.player().at(0.5, 0, 0.5, -90, 20);
		String inputs = a.x(2) + " " + a.y(0) + " " + a.z(2) + "; " + a.x(2) + " " + a.y(0) + " " + a.z(-2);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:ender_chest").set("inputs", inputs)
			.set("outputs", a.x(-1) + " " + a.y(0) + " " + a.z(2));
		t.waitTicks(10);
	}

	@Test(name = "containers_under_chat", tags = "inventory", timeoutTicks = 1200)
	public void containersUnderChat(TestContext t) {
		// Chat open the whole time (as the UI or a wheel would be): it stays up, never replaced or closed, and the ender
		// chests are made all the same (Auto Craft runs as a job, whose containers open with no screen).
		enderChestLine(t);
		t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
		t.waitTicks(2);
		var chat = t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.screen());
		t.flagsFromHere();
		t.module(CRAFT).enable();
		int made = 0, lost = 0;
		for (int i = 0; i < 120 && made < 24; i++) {
			for (int k = 0; k < 5; k++) {
				t.waitTicks(1);
				if (t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.screen()) != chat) lost++;
			}
			made = stored(t, -1, 0, 2, "ender_chest");
		}
		t.module(CRAFT).disable();
		t.module(CRAFT).set("inputs", "").set("outputs", "");
		t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(null));
		String detail = String.format(Locale.ROOT, "%d ender chests made; chat replaced or closed for %d ticks", made, lost);
		t.check(made == 24 && lost == 0, detail);
		t.note(detail);
	}

	@Test(name = "containers_inventory_open", tags = "inventory", timeoutTicks = 1600)
	public void containersInventoryOpen(TestContext t) {
		// Your inventory opened partway: it stays up, what Auto Craft had open is closed on the server (so your clicks
		// count), nothing more is made while it's up, and once it's closed the rest is.
		enderChestLine(t);
		t.flagsFromHere();
		t.module(CRAFT).enable();
		t.waitUntil("some made", () -> stored(t, -1, 0, 2, "ender_chest") > 0 || count(t, "ender_chest") > 0, 400);
		t.onGame(() -> {
			var mc = net.minecraft.client.Minecraft.getInstance();
			mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
		});
		t.waitTicks(10);
		int before = stored(t, -1, 0, 2, "ender_chest") + count(t, "ender_chest");
		int stayed = 0, yours = 0;
		for (int i = 0; i < 40; i++) {
			t.waitTicks(1);
			if (t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.screen()
				instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) stayed++;
			if (t.onGame(() -> net.minecraft.client.Minecraft.getInstance().player.containerMenu
				== net.minecraft.client.Minecraft.getInstance().player.inventoryMenu)) yours++;
		}
		int during = stored(t, -1, 0, 2, "ender_chest") + count(t, "ender_chest") - before;
		t.onGame(() -> net.minecraft.client.Minecraft.getInstance().player.closeContainer());
		boolean rest = t.waitUntil("the rest made", () -> stored(t, -1, 0, 2, "ender_chest") >= 24, 800);
		t.module(CRAFT).disable();
		t.module(CRAFT).set("inputs", "").set("outputs", "");
		String detail = String.format(Locale.ROOT, "inventory stayed up %d/40 ticks, yours on the server %d/40, %d made while it was up, %d in the output after",
			stayed, yours, during, stored(t, -1, 0, 2, "ender_chest"));
		t.check(stayed == 40 && yours == 40 && during == 0 && rest, detail);
		t.note(detail);
	}

	@Test(name = "containers_rounds", tags = "inventory", timeoutTicks = 2400)
	public void containersRounds(TestContext t) {
		// Only six slots free (the rest dirt): 96 ender chests (768 obsidian, 96 eyes) take a few rounds of fetching,
		// crafting and putting away; the dirt stays.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		var a = t.arena();
		t.arena().set(2, 0, 0, "crafting_table");
		t.arena().set(2, 0, 2, "chest");
		t.arena().set(2, 0, -2, "barrel");
		t.arena().set(-1, 0, 2, "chest");
		for (int slot = 0; slot < 12; slot++)
			t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(2) + " container." + slot + " with obsidian 64");
		t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(-2) + " container.0 with ender_eye 64");
		t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(-2) + " container.1 with ender_eye 32");
		for (int i = 0; i < 30; i++) t.player().give(i, "dirt", 64);
		t.player().at(0.5, 0, 0.5, -90, 20);
		String inputs = a.x(2) + " " + a.y(0) + " " + a.z(2) + "; " + a.x(2) + " " + a.y(0) + " " + a.z(-2);
		String output = a.x(-1) + " " + a.y(0) + " " + a.z(2);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:ender_chest").set("inputs", inputs).set("outputs", output);
		t.waitTicks(10);
		t.flagsFromHere();
		long start = now(t);
		t.module(CRAFT).enable();
		int made = 0;
		for (int i = 0; i < 300 && made < 96; i++) {
			t.waitTicks(5);
			made = stored(t, -1, 0, 2, "ender_chest");
		}
		long took = now(t) - start;
		t.waitTicks(20);
		t.module(CRAFT).disable();
		t.module(CRAFT).set("inputs", "").set("outputs", "");
		int obsidian = stored(t, 2, 0, 2, "obsidian"), eyes = stored(t, 2, 0, -2, "ender_eye"), dirt = count(t, "dirt");
		int kept = count(t, "ender_chest") + count(t, "obsidian") + count(t, "ender_eye");
		boolean dropped = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..10]");
		String detail = String.format(Locale.ROOT, "%d ender chests in the output in %d ticks; inputs left %d obsidian, %d eyes; %d kept, %d dirt%s", made, took,
			obsidian, eyes, kept, dirt, dropped ? ", items on the ground" : "");
		t.check(made == 96 && obsidian == 0 && eyes == 0 && kept == 0 && dirt == 30 * 64 && !dropped, detail);
		t.note(detail);
	}

	@Test(name = "containers_swapped", tags = "inventory", timeoutTicks = 2400)
	public void containersSwapped(TestContext t) {
		// The eyes in a shulker box that's swapped out once it's empty, as a shulker unloader does: gone for a moment, then
		// a new box with 12 more. Both boxes' worth crafted (24 ender chests), soon after the new one is there, but nothing
		// taken from the new box for its first 10 ticks: an unloader's comparator has to see it full, or it never swaps again.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		var a = t.arena();
		String box = a.x(2) + " " + a.y(0) + " " + a.z(-2);
		t.arena().set(2, 0, 0, "crafting_table");
		t.arena().set(2, 0, 2, "chest");
		t.arena().set(2, 0, -2, "shulker_box");
		t.arena().set(-1, 0, 2, "chest");
		for (int slot = 0; slot < 3; slot++)
			t.server().run("item replace block " + a.x(2) + " " + a.y(0) + " " + a.z(2) + " container." + slot + " with obsidian 64");
		t.server().run("item replace block " + box + " container.0 with ender_eye 12");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:ender_chest")
			// The box first, so it's the first thing opened when the new one comes.
			.set("inputs", box + "; " + a.x(2) + " " + a.y(0) + " " + a.z(2)).set("outputs", a.x(-1) + " " + a.y(0) + " " + a.z(2));
		t.waitTicks(10);
		t.flagsFromHere();
		t.module(CRAFT).enable();
		boolean first = t.waitUntil("the first box's 12 put away", () -> stored(t, -1, 0, 2, "ender_chest") >= 12, 400);
		t.server().run("setblock " + box + " air");
		t.waitTicks(30);
		// Full as it arrives, as an unloader's next box is.
		t.server().run("setblock " + box + " shulker_box{Items:[{Slot:0b,id:\"minecraft:ender_eye\",count:12}]}");
		long placed = now(t);
		long firstTake = -1;
		for (int i = 0; i < 60 && firstTake < 0; i++) {
			if (stored(t, 2, 0, -2, "ender_eye") < 12) firstTake = now(t) - placed;
			else t.waitTicks(1);
		}
		boolean second = t.waitUntil("the second box's 12 put away", () -> stored(t, -1, 0, 2, "ender_chest") >= 24, 600);
		long took = now(t) - placed;
		t.module(CRAFT).disable();
		t.module(CRAFT).set("inputs", "").set("outputs", "");
		int made = stored(t, -1, 0, 2, "ender_chest"), obsidian = stored(t, 2, 0, 2, "obsidian");
		boolean dropped = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..10]");
		String detail = String.format(Locale.ROOT, "%d ender chests, the second box's %d ticks after it was there (first taken from at %d); %d obsidian left%s",
			made, took, firstTake, obsidian, dropped ? ", items on the ground" : "");
		t.check(first && second && made == 24 && obsidian == 0 && took <= 160 && firstTake >= 10 && !dropped, detail);
		t.note(detail);
	}

	/** Setting {@code key}'s value, read on the game thread. */
	private static String setting(TestContext t, String key) {
		return t.onGame(() -> t.module(CRAFT).get().settings.get(key).map(s -> String.valueOf(s.get())).orElse("?"));
	}

	/** Sends {@code line} as chat, which runs it as a Myriad command. */
	private static void command(TestContext t, String line) {
		t.onGame(() -> net.minecraft.client.Minecraft.getInstance().player.connection.sendChat(line));
		t.waitTicks(2);
	}

	@Test(name = "containers_command", tags = "inventory", timeoutTicks = 400)
	public void containersCommand(TestContext t) {
		// .containers by the chest you look at: input toggles it on and off, output add moves it over, clear empties.
		var a = t.arena();
		t.arena().set(0, 0, 2, "chest");
		t.player().at(0.5, 0, 0.5, 0, 30);
		t.module(CRAFT).set("inputs", "").set("outputs", "");
		t.waitTicks(10);
		String chest = a.x(0) + " " + a.y(0) + " " + a.z(2);
		command(t, ".containers AutoCraft input");
		boolean added = setting(t, "inputs").equals(chest);
		command(t, ".containers autocraft input");
		boolean toggledOff = setting(t, "inputs").isEmpty();
		command(t, ".containers AutoCraft input add");
		command(t, ".containers AutoCraft output add");
		boolean moved = setting(t, "inputs").isEmpty() && setting(t, "outputs").equals(chest);
		command(t, ".containers AutoCraft output remove");
		boolean removed = setting(t, "outputs").isEmpty();
		command(t, ".containers AutoCraft output");
		command(t, ".containers AutoCraft output clear");
		boolean cleared = setting(t, "outputs").isEmpty();
		String detail = String.format(Locale.ROOT, "added %b, toggled off %b, moved to outputs %b, removed %b, cleared %b", added, toggledOff, moved, removed, cleared);
		t.check(added && toggledOff && moved && removed && cleared, detail);
		t.note(detail);
	}
}
