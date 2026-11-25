package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.List;
import java.util.Locale;

/**
 * Auto Rename: the listed item renamed at an anvil in reach, each stack back in its own slot, other items left alone, a
 * level a stack; a stack too expensive to rename left where it was.
 */
public final class AutoRenameTests extends EssentialsSuite {
	private static final String RENAME = "myriad-essentials:auto_rename";

	private static void setUp(TestContext t) {
		t.server().run("xp set " + t.player().name() + " 30 levels");
		t.module(RENAME).set("items", "clear").set("items", "add minecraft:diamond").set("name", "Loot");
	}

	private static boolean has(TestContext t, String where, String item) {
		return t.server().passes("if items " + where + " " + item);
	}

	private static int levels(TestContext t) {
		return t.onGame(() -> net.minecraft.client.Minecraft.getInstance().player.experienceLevel);
	}

	@Test(tags = "inventory", timeoutTicks = 800)
	public void inventory(TestContext t) {
		t.arena().set(2, 0, 0, "anvil");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.player().give(9, "diamond", 64).give(20, "diamond", 5).give(3, "dirt", 32).give(0, "diamond_sword");
		setUp(t);
		t.waitTicks(5);
		t.flagsFromHere();
		String me = "entity " + t.player().name();
		t.module(RENAME).enable();
		boolean done = false;
		for (int i = 0; i < 40 && !done; i++) {
			t.waitTicks(5);
			done = !has(t, me, "container.* diamond[!custom_name]");
		}
		t.waitTicks(20);
		t.module(RENAME).disable();
		List<String> inv = t.player().inventory();
		boolean layout = has(t, me, "container.9 diamond[custom_name=\"Loot\"]") && has(t, me, "container.20 diamond[custom_name=\"Loot\"]")
			&& inv.get(9).equals("diamond x64") && inv.get(20).equals("diamond x5") && inv.get(3).equals("dirt x32")
			&& has(t, me, "container.3 dirt[!custom_name]") && has(t, me, "container.0 diamond_sword[!custom_name]");
		boolean screen = t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.screen() == null
			&& net.minecraft.client.Minecraft.getInstance().player.containerMenu == net.minecraft.client.Minecraft.getInstance().player.inventoryMenu);
		int lv = levels(t);
		String detail = String.format(Locale.ROOT, "%s, slots %s, levels %d, anvil %s", done ? "renamed" : "not renamed", layout ? "as they were" : inv.subList(0, 21),
			lv, screen ? "closed" : "still open");
		t.check(done && layout && lv == 28 && screen, detail);
		t.note(detail);
	}

	@Test(name = "under_chat", tags = "inventory", timeoutTicks = 800)
	public void underChat(TestContext t) {
		// Chat open the whole time (as the UI or a wheel would be): it stays up, and the stacks are renamed all the same
		// (the anvil, a job's, opens with no screen).
		t.arena().set(2, 0, 0, "anvil");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.player().give(9, "diamond", 64).give(20, "diamond", 5);
		setUp(t);
		t.waitTicks(5);
		t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
		t.waitTicks(2);
		var chat = t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.screen());
		t.flagsFromHere();
		String me = "entity " + t.player().name();
		t.module(RENAME).enable();
		boolean done = false;
		int lost = 0;
		for (int i = 0; i < 200 && !done; i++) {
			t.waitTicks(1);
			if (t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.screen()) != chat) lost++;
			if (i % 5 == 4) done = !has(t, me, "container.* diamond[!custom_name]");
		}
		t.module(RENAME).disable();
		t.onGame(() -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(null));
		String detail = String.format(Locale.ROOT, "%s; chat replaced or closed for %d ticks", done ? "renamed" : "not renamed", lost);
		t.check(done && lost == 0, detail);
		t.note(detail);
	}

	@Test(name = "without_keeping_slots", tags = "inventory", timeoutTicks = 800)
	public void withoutKeepingSlots(TestContext t) {
		// Keep Slots off: two clicks a stack, each renamed stack wherever there's room (stacking with the others); all renamed.
		t.arena().set(2, 0, 0, "anvil");
		t.player().at(0.5, 0, 0.5, -90, 20);
		for (int i = 9; i < 15; i++) t.player().give(i, "diamond", 1 + i);
		setUp(t);
		t.module(RENAME).set("keep_slots", "false");
		t.waitTicks(5);
		t.flagsFromHere();
		String me = "entity " + t.player().name();
		long start = t.onGame(() -> (long) dev.myriadclient.api.util.Ticks.current());
		t.module(RENAME).enable();
		boolean done = false;
		for (int i = 0; i < 40 && !done; i++) {
			t.waitTicks(2);
			done = !has(t, me, "container.* diamond[!custom_name]");
		}
		long took = t.onGame(() -> (long) dev.myriadclient.api.util.Ticks.current()) - start;
		t.module(RENAME).disable();
		t.module(RENAME).set("keep_slots", "true");
		// Renamed alike, they stack together where they land: counted by diamonds, not stacks.
		int diamonds = 0;
		for (String item : t.player().inventory()) if (item.startsWith("diamond x")) diamonds += Integer.parseInt(item.substring("diamond x".length()));
		String detail = String.format(Locale.ROOT, "%s, %d of 75 diamonds kept, in %d ticks", done ? "all renamed" : "not all renamed", diamonds, took);
		t.check(done && diamonds == 75, detail);
		t.note(detail);
	}

	@Test(name = "too_expensive", tags = "inventory", timeoutTicks = 600)
	public void tooExpensive(TestContext t) {
		// A sword worked on so often the anvil won't take it, and a fresh one: the fresh one renamed, the other left in its slot.
		t.arena().set(2, 0, 0, "anvil");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.player().give(5, "diamond_sword[repair_cost=45]").give(6, "diamond_sword");
		setUp(t);
		t.module(RENAME).set("items", "add minecraft:diamond_sword");
		t.waitTicks(5);
		t.flagsFromHere();
		String me = "entity " + t.player().name();
		t.module(RENAME).enable();
		t.waitTicks(80);
		t.module(RENAME).disable();
		boolean fresh = has(t, me, "container.6 diamond_sword[custom_name=\"Loot\"]");
		boolean old = has(t, me, "container.5 diamond_sword[!custom_name]");
		boolean closed = t.onGame(() -> net.minecraft.client.Minecraft.getInstance().player.containerMenu
			== net.minecraft.client.Minecraft.getInstance().player.inventoryMenu);
		String detail = String.format(Locale.ROOT, "fresh sword %s, worked one %s, levels %d, anvil %s", fresh ? "renamed" : "not renamed",
			old ? "left in its slot" : "moved or renamed", levels(t), closed ? "closed" : "open");
		t.check(fresh && old && closed && levels(t) == 29, detail);
		t.note(detail);
	}
}
