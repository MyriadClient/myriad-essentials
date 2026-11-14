package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.ArrayList;
import java.util.List;

import static dev.myriadclient.essentials.gametest.BoxScenes.SILK;
import static dev.myriadclient.essentials.gametest.BoxScenes.any;
import static dev.myriadclient.essentials.gametest.BoxScenes.holds;
import static dev.myriadclient.essentials.gametest.BoxScenes.screen;

/**
 * Inventory Tweaks' Remote Ender Chest: an ender chest closed with Escape stays open on the server, so the inventory key
 * shows it again, from anywhere once it's held, and your inventory's clicks and moves go through it; boxes taken out of
 * it go back through it, another ender chest or your inventory, as No Way Back says.
 */
public final class RemoteEnderChestTests extends EssentialsSuite {
	/**
	 * Where a remote box pass left the shulker box, whether an ender chest was left standing, whether the one put down
	 * came back, and the screens seen.
	 */
	private record RemoteBox(String where, boolean placed, boolean back, List<String> screens) {
	}

	/**
	 * An ender chest beside the tester (at (2, 0, 0)), a stack of diamonds in their ender chest's first slot, sticks in
	 * hotbar slot 3, Inventory Tweaks' Remote Ender Chest on: it's opened by hand and closed with Escape, which keeps it
	 * open on the server. The screens after opening it and after Escape.
	 */
	private static List<String> remoteEchestSetup(TestContext t) {
		t.player().at(0.5, 0, 0.5, -90, 35);
		t.arena().set(2, 0, 0, "ender_chest[facing=west]");
		BoxScenes.enderChest(t, 0, "diamond 7");
		t.player().give(3, "stick", 5);
		t.module("myriad-essentials:inventory_tweaks").set("remote_ender_chest", "true").enable();
		t.waitTicks(5);
		t.step("use");
		t.waitTicks(15);
		List<String> screens = new ArrayList<>();
		screens.add(screen(t));
		t.step("key escape");
		t.waitTicks(10);
		screens.add(screen(t));
		return screens;
	}

	/** Takes the diamonds from the open ender chest's first slot into hotbar slot 0 (slot 54 of its screen). */
	private static void takeDiamonds(TestContext t) {
		t.step("cursor slot 0", "mouse 0 click", "cursor slot 54", "mouse 0 click");
		t.waitTicks(10);
	}

	/** Whether the diamonds are in {@code where} on the server and gone from the ender chest. */
	private static boolean taken(TestContext t, String where) {
		return holds(t, where, "diamond") && !holds(t, "enderchest.0", "diamond");
	}

	/** Teleports 20 blocks east (x 22.5), keeping the look: past where a server closes an ender chest. */
	private static void goFarAway(TestContext t) {
		t.server().run("tp " + t.player().name() + " " + t.arena().at(22.5, 0, 0.5));
	}

	private static void report(TestContext t, boolean ok, String detail) {
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "boxes")
	public void remoteEnderChest(TestContext t) {
		// Opened again with the inventory key, no click on the block, and its diamonds taken into hotbar slot 1: the server
		// took the clicks in the ender chest it still had open.
		List<String> sc = remoteEchestSetup(t);
		t.step("key e");
		t.waitTicks(10);
		sc.add(screen(t));
		takeDiamonds(t);
		t.step("key escape");
		t.waitTicks(5);
		boolean taken = taken(t, "hotbar.0");
		boolean ok = sc.equals(List.of("ContainerScreen", "none", "ContainerScreen")) && taken;
		report(t, ok, "screens " + sc + ", " + (taken ? "diamonds taken from the ender chest" : "the diamonds didn't move on the server"));
	}

	@Test(tags = "boxes")
	public void moves(TestContext t) {
		// With its screen gone, your inventory's clicks go through it: a move (core's, as modules make them) lands on the server.
		remoteEchestSetup(t);
		t.step("move 3 20");
		t.waitTicks(10);
		boolean moved = holds(t, "inventory.11", "stick") && !holds(t, "hotbar.3", "stick");
		report(t, moved, moved ? "the move went through the held ender chest" : "the move didn't reach the server");
	}

	@Test(tags = "boxes")
	public void farAway(TestContext t) {
		// Opened once more over itself when held, it's freed from its block: 20 blocks away, past where a server closes an
		// ender chest, the inventory key shows it and its diamonds come out. Your inventory's tab leaves it open: the diamonds
		// moved there (hotbar to inventory) move on the server, and its tab, then the inventory key, show the ender chest again.
		List<String> sc = remoteEchestSetup(t);
		goFarAway(t);
		t.waitTicks(40);
		t.step("key e");
		t.waitTicks(10);
		sc.add(screen(t));
		takeDiamonds(t);
		t.step("tab 0");
		t.waitTicks(10);
		sc.add(screen(t));
		// Your inventory's screen: hotbar slot 0 is its slot 36, the inventory's first slot its slot 9.
		t.step("cursor slot 36", "mouse 0 click", "cursor slot 9", "mouse 0 click");
		t.waitTicks(10);
		t.step("tab 1");
		t.waitTicks(5);
		sc.add(screen(t));
		t.step("key escape");
		t.waitTicks(5);
		t.step("key e");
		t.waitTicks(10);
		sc.add(screen(t));
		t.step("key escape");
		t.waitTicks(5);
		boolean taken = taken(t, "inventory.0");
		boolean ok = sc.equals(List.of("ContainerScreen", "none", "ContainerScreen", "InventoryScreen", "ContainerScreen", "ContainerScreen")) && taken;
		report(t, ok, "screens " + sc + ", " + (taken ? "diamonds taken 20 blocks away and moved in your inventory's screen" : "the diamonds didn't move on the server"));
	}

	/**
	 * An ender chest Inventory Tweaks puts down from your inventory, with its Remote Ender Chest on: Escape picks it back up
	 * into its slot, and it stays open: the inventory key shows it and its diamonds come out.
	 */
	@Test(tags = "boxes", timeoutTicks = 2000)
	public void putDown(TestContext t) {
		t.player().at(0.5, 0, 0.5, -90, 35);
		t.player().give(0, "diamond_sword").give(6, SILK).give(14, "ender_chest");
		BoxScenes.enderChest(t, 0, "diamond 7");
		t.module("myriad-essentials:inventory_tweaks").set("remote_ender_chest", "true").set("air_place", "false").enable();
		t.player().select(1);
		t.waitTicks(10);
		t.step("key e");
		t.waitTicks(10);
		t.step("cursor slot 14", "mouse 1 click alt");
		t.waitTicks(40);
		List<String> sc = new ArrayList<>();
		sc.add(screen(t));
		t.step("key escape");
		t.waitTicks(300);
		sc.add(screen(t));
		boolean standing = any(t, -1, 0, -1, 1, 3, 1, "ender_chest");
		boolean home = holds(t, "container.*", "ender_chest");
		t.step("key e");
		t.waitTicks(10);
		sc.add(screen(t));
		takeDiamonds(t);
		t.step("key escape");
		t.waitTicks(5);
		boolean taken = taken(t, "hotbar.0");
		boolean obsidian = holds(t, "container.*", "obsidian");
		boolean ok = sc.equals(List.of("ContainerScreen", "none", "ContainerScreen")) && !standing && home && taken && !obsidian;
		report(t, ok, "screens " + sc + (standing ? ", still standing" : ", picked up") + (home ? ", back in your inventory" : ", not back in your inventory")
			+ (taken ? ", diamonds taken from it after" : ", the diamonds didn't move") + (obsidian ? ", OBSIDIAN" : ""));
	}

	@Test(tags = "boxes")
	public void armour(TestContext t) {
		// On your inventory's tab the held ender chest stays open until a click on what it doesn't show: a helmet carried
		// from the hotbar onto the head slot lets it go (the close puts the helmet back, picked up again for the click) and
		// goes on; the inventory key shows your inventory after.
		remoteEchestSetup(t);
		t.player().give(1, "diamond_helmet");
		t.step("key e");
		t.waitTicks(10);
		t.step("tab 0");
		t.waitTicks(10);
		List<String> sc = new ArrayList<>();
		sc.add(screen(t));
		// Hotbar slot 1 is slot 37 of your inventory's screen, the head slot its slot 5.
		t.step("cursor slot 37", "mouse 0 click");
		t.waitTicks(2);
		t.step("cursor slot 5", "mouse 0 click");
		t.waitTicks(10);
		t.step("key escape");
		t.waitTicks(5);
		t.step("key e");
		t.waitTicks(10);
		sc.add(screen(t));
		t.step("key escape");
		t.waitTicks(5);
		boolean worn = holds(t, "armor.head", "diamond_helmet");
		boolean ok = sc.equals(List.of("InventoryScreen", "InventoryScreen")) && worn;
		report(t, ok, "screens " + sc + ", " + (worn ? "helmet on" : "helmet not on"));
	}

	/**
	 * A shulker box in your remote ender chest, held 20 blocks away, Mod + right-clicked: put down, and on Escape back
	 * through another ender chest in reach ({@code nearby}, at (24, 0, 0)), one put down from your inventory (No Way Back:
	 * Ender Chest; {@code enderItem}: two ender chests in inventory slot 19), or into your inventory (Inventory); with none
	 * of these it isn't taken out (Refuse).
	 */
	private static RemoteBox remoteBoxPass(TestContext t, String mode, boolean nearby, boolean enderItem) {
		List<String> sc = remoteEchestSetup(t);
		BoxScenes.enderChest(t, 1, "red_shulker_box");
		t.player().give(6, SILK);
		if (nearby) t.arena().set(24, 0, 0, "ender_chest[facing=west]");
		if (enderItem) t.player().give(19, "ender_chest", 2);
		goFarAway(t);
		t.module("myriad-essentials:inventory_tweaks").set("no_way_back", mode).set("air_place", "false");
		t.waitTicks(40);
		t.step("key e");
		t.waitTicks(10);
		sc.add(screen(t));
		t.step("cursor slot 1", "mouse 1 click alt");
		t.waitTicks(40);
		sc.add(screen(t));
		String tabs = String.join(" ", t.step("tabs"));
		t.step("key escape");
		t.waitTicks(300);
		sc.add(screen(t));
		boolean inEnder = holds(t, "enderchest.*", "red_shulker_box");
		boolean yours = holds(t, "container.*", "red_shulker_box");
		boolean standing = any(t, 19, 0, -1, 26, 3, 2, "#minecraft:shulker_boxes");
		// An ender chest left standing anywhere but the one set there.
		boolean placed = any(t, 19, 0, -1, 23, 3, 2, "ender_chest") || any(t, 25, 0, -1, 26, 3, 2, "ender_chest");
		boolean enderBack = !enderItem || t.player().count(19) == 2;
		t.step("key e");
		t.waitTicks(10);
		sc.add(screen(t));
		t.step("key escape");
		t.waitTicks(5);
		String where = standing ? "standing" : inEnder ? "in the ender chest" : yours ? "in your inventory" : "LOST";
		// Taken out: its tab on the swap bar.
		boolean taken = tabs.matches("(?s).*tabs: .* box.*");
		return new RemoteBox((taken ? "taken out, back " : "not taken out, ") + where, placed, enderBack, sc);
	}

	@Test(tags = "boxes", timeoutTicks = 2400)
	public void boxNearby(TestContext t) {
		// Back through the ender chest in reach (every one holds the same things), Refuse or not; that one held after, so the
		// inventory key shows it.
		RemoteBox r = remoteBoxPass(t, "refuse", true, false);
		boolean ok = r.where.equals("taken out, back in the ender chest") && !r.placed && r.screens.getLast().equals("ContainerScreen");
		report(t, ok, r.where + ", screens " + r.screens);
	}

	@Test(tags = "boxes", timeoutTicks = 2400)
	public void boxRefused(TestContext t) {
		// No other ender chest in reach and Refuse: it isn't taken out.
		RemoteBox r = remoteBoxPass(t, "refuse", false, false);
		report(t, r.where.equals("not taken out, in the ender chest"), r.where + ", screens " + r.screens);
	}

	@Test(tags = "boxes", timeoutTicks = 2400)
	public void boxPlacedEnderChest(TestContext t) {
		// No Way Back: Ender Chest: one from your inventory is put down for it, and picked back up.
		RemoteBox r = remoteBoxPass(t, "ender_chest", false, true);
		boolean ok = r.where.equals("taken out, back in the ender chest") && !r.placed && r.back;
		report(t, ok, r.where + (r.placed ? ", an ender chest LEFT STANDING" : "") + (r.back ? "" : ", the ender chest not back in your inventory")
			+ ", screens " + r.screens);
	}

	@Test(tags = "boxes", timeoutTicks = 2400)
	public void boxInventory(TestContext t) {
		// No Way Back: Inventory: taken out anyway. Since core 1.7.2 a box from the held ender chest goes straight back into
		// it from any distance (it's still held); into your inventory only once it isn't.
		RemoteBox r = remoteBoxPass(t, "inventory", false, false);
		boolean ok = r.where.equals("taken out, back in the ender chest") || r.where.equals("taken out, back in your inventory");
		report(t, ok, r.where + ", screens " + r.screens);
	}
}
