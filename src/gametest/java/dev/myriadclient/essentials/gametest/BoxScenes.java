package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Map;

/**
 * What the box suites (Open Box, Remote Ender Chest) share: items written as {@code "cobblestone 32"}, the server's view
 * of what the player holds, and the screen that's up.
 */
final class BoxScenes {
	/** The pickaxe an ender chest is broken with, so it drops itself rather than obsidian. */
	static final String SILK = "diamond_pickaxe[enchantments={silk_touch:1}]";

	private BoxScenes() {
	}

	/**
	 * The open screen's class name ({@code "ContainerScreen"}, {@code "InventoryScreen"}), or {@code "none"}: what core's
	 * {@code status} step reports as {@code screen=}.
	 */
	static String screen(TestContext t) {
		return t.onGame(() -> {
			Screen s = Minecraft.getInstance().gui.screen();
			return s == null ? "none" : s.getClass().getSimpleName();
		});
	}

	/** Puts each item ({@code "cobblestone 32"}, one without a count) in its inventory slot (0-8 hotbar, 9-35 inventory). */
	static void give(TestContext t, Map<Integer, String> items) {
		for (Map.Entry<Integer, String> e : items.entrySet()) t.player().give(e.getKey(), item(e.getValue()), amount(e.getValue()));
	}

	/** The item with its components but without the count: {@code "ender_chest"} for {@code "ender_chest 4"}. */
	static String item(String spec) {
		int space = spec.indexOf(' ');
		return space < 0 ? spec : spec.substring(0, space);
	}

	/** The item id alone: {@code "diamond_pickaxe"} for {@code "diamond_pickaxe[enchantments=...]"} or one with a count. */
	static String id(String spec) {
		String item = item(spec);
		int bracket = item.indexOf('[');
		return bracket < 0 ? item : item.substring(0, bracket);
	}

	/** The count in {@code "ender_chest 4"} (1 with none). */
	static int amount(String spec) {
		int space = spec.indexOf(' ');
		return space < 0 ? 1 : Integer.parseInt(spec.substring(space + 1).strip());
	}

	/** An inventory slot (0-35) as item commands and tests name it: {@code "hotbar.3"}, {@code "inventory.5"}. */
	static String slot(int slot) {
		return slot < 9 ? "hotbar." + slot : "inventory." + (slot - 9);
	}

	/**
	 * Whether the player's {@code where} ({@code "hotbar.0"}, {@code "container.*"}, {@code "enderchest.0"}) holds
	 * {@code item} on the server ({@code "diamond"}, or an item predicate).
	 */
	static boolean holds(TestContext t, String where, String item) {
		return t.server().passes("if items entity " + t.player().name() + " " + where + " " + item);
	}

	/** Puts {@code item} ({@code "diamond 7"}) in slot {@code slot} of the player's ender chest, on the server. */
	static void enderChest(TestContext t, int slot, String item) {
		t.server().run("item replace entity " + t.player().name() + " enderchest." + slot + " with " + item);
	}

	/** The item id in a container's slot, from {@code TestContext.Arena#items} ({@code ""} if it's empty). */
	static String idAt(List<String> items, int slot) {
		String entry = items.get(slot);
		int at = entry.lastIndexOf(" x");
		return at < 0 ? entry : entry.substring(0, at);
	}

	/** Whether any block in the box between two corners (relative, inclusive) is {@code block}: stops at the first. */
	static boolean any(TestContext t, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) if (t.arena().is(x, y, z, block)) return true;
		return false;
	}
}
