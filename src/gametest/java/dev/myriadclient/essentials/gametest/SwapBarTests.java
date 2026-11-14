package dev.myriadclient.essentials.gametest;

import dev.myriadclient.essentials.gametest.SortScenes.Stack;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The swap bar: shulker boxes Mod + right-clicked in a chest (or your inventory) are put down and stand while you switch
 * between their tabs, then go back where they came from on Escape, from a full inventory or hotbar, with the chest out
 * of reach, several at once; an ender chest in reach is a tab of its own. You start facing east (+x) looking down, the
 * chest two blocks ahead at (2, 0, 0).
 */
public final class SwapBarTests extends EssentialsSuite {
	private static final Pattern COUNT = Pattern.compile("count: (\\d+)"), STATUS_X = Pattern.compile("status: pos (\\S+) ");

	/** At the start facing the chest's spot (east), looking down at it. */
	private static void start(TestContext t) {
		t.player().at(0.5, 0, 0.5, -90, 35);
	}

	/** Inventory Tweaks on, putting boxes down beside you rather than overhead. */
	private static void tweaks(TestContext t) {
		t.module(SortScenes.INVENTORY_TWEAKS).set("air_place", "false").enable();
	}

	/** How many shulker boxes stand at x {@code xs}, z -1 to 1, y 0 to {@code top}. */
	private static int standing(TestContext t, int[] xs, int top) {
		int n = 0;
		for (int x : xs) for (int z = -1; z <= 1; z++) for (int y = 0; y <= top; y++) if (t.arena().is(x, y, z, "#minecraft:shulker_boxes")) n++;
		return n;
	}

	/** How many shulker boxes the chest at (2, 0, 0) holds, as the server counts them. */
	private static int boxesInChest(TestContext t) {
		Matcher m = COUNT.matcher(t.server().run("execute if items block " + t.arena().block(2, 0, 0) + " container.* #minecraft:shulker_boxes"));
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	/** The diamond sword in hotbar slot 0, the pickaxe in 6 and nothing else in the hotbar, as each of these tests starts. */
	private static boolean hotbarKept(TestContext t) {
		if (!t.player().item(0).equals("minecraft:diamond_sword") || !t.player().item(6).equals("minecraft:diamond_pickaxe")) return false;
		for (int h : new int[]{1, 2, 3, 4, 5, 7, 8}) if (t.player().count(h) > 0) return false;
		return true;
	}

	private static <T> List<T> first(List<T> list) {
		return list.subList(0, Math.min(1, list.size()));
	}

	@Test(tags = "boxes")
	public void twoBoxes(TestContext t) {
		// Two shulker boxes Mod + right-clicked in a chest opened by hand: both put down with the chest still up, then switched
		// between on the swap bar (the first box, the second, the chest, the first again): they stand while you switch. Escape
		// puts both back in their slots in the chest and closes everything.
		start(t);
		t.arena().set(2, 0, 0, "chest[facing=west]");
		t.player().give(0, "diamond_sword").give(6, "diamond_pickaxe");
		t.server().run("item replace block " + t.arena().block(2, 0, 0) + " container.4 with red_shulker_box");
		t.server().run("item replace block " + t.arena().block(2, 0, 0) + " container.6 with blue_shulker_box");
		tweaks(t);
		t.player().select(1);
		List<String> out = new ArrayList<>(SortScenes.script(t, "wait 10", "use", "wait 15",
			"cursor slot 4", "mouse 1 click alt", "wait 30", "cursor slot 6", "mouse 1 click alt", "wait 30", "status", "tabs",
			"tab 2", "wait 20", "status", "tabs", "tab 3", "wait 20", "status", "tabs", "tab 1", "wait 20", "status", "tabs",
			"tab 2", "wait 20", "status", "tabs"));
		int standing = standing(t, new int[]{1, 0, -1, 2}, 3);
		out.addAll(SortScenes.script(t, "key escape", "wait 400", "status"));
		List<String> sc = SortScenes.screens(out);
		List<Stack> items = SortScenes.chestItems(t, 2, 0, 0);
		boolean back = items.stream().filter(s -> s != null && (s.item().equals("red_shulker_box") || s.item().equals("blue_shulker_box"))).count() == 2;
		boolean hotbarOk = hotbarKept(t);
		List<String> want = List.of("ContainerScreen", "ShulkerBoxScreen", "ShulkerBoxScreen", "ContainerScreen", "ShulkerBoxScreen", "none");
		boolean ok = sc.equals(want) && standing == 2 && back && hotbarOk;
		String detail = "screens " + sc + ", " + standing + " boxes standing while switching" + (back ? ", both back in the chest" : ", chest " + SortScenes.filled(items))
			+ (hotbarOk ? "" : ", hotbar not as it was") + (ok ? "" : ", tabs " + SortScenes.tabs(out));
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "boxes")
	public void threeBoxes(TestContext t) {
		// Three shulker boxes put down from a chest opened by hand, two of them red (one holding dirt, one stone): Escape breaks
		// them one after another and puts them back together, the chest opened once, each with what was in it. Reports the time
		// from Escape to all three back in the chest.
		start(t);
		t.arena().set(2, 0, 0, "chest[facing=west]");
		t.player().give(0, "diamond_sword").give(6, "diamond_pickaxe");
		String chest = "item replace block " + t.arena().block(2, 0, 0);
		t.server().run(chest + " container.4 with red_shulker_box[container=[{slot:0,item:{id:dirt,count:1}}]]");
		t.server().run(chest + " container.5 with red_shulker_box[container=[{slot:0,item:{id:stone,count:1}}]]");
		t.server().run(chest + " container.6 with blue_shulker_box");
		tweaks(t);
		t.player().select(1);
		List<String> out = new ArrayList<>(SortScenes.script(t, "wait 10", "use", "wait 15",
			"cursor slot 4", "mouse 1 click alt", "wait 30", "cursor slot 5", "mouse 1 click alt", "wait 30",
			"cursor slot 6", "mouse 1 click alt", "wait 30", "status", "tabs"));
		int standing = standing(t, new int[]{1, 0, -1, 2}, 3);
		t.step("key escape");
		long start = System.nanoTime();
		Double took = null;
		while (System.nanoTime() - start < 20_000_000_000L) {
			// One check: a box with things in it is too much to read back whole.
			if (boxesInChest(t) == 3) {
				took = (System.nanoTime() - start) / 1e9;
				break;
			}
			t.waitTicks(2);
		}
		out.addAll(SortScenes.script(t, "wait 20", "status"));
		StringBuilder inside = new StringBuilder();
		for (int i = 0; i < 3; i++) inside.append(t.arena().data(2, 0, 0, "Items[" + i + "].components.\"minecraft:container\"[0].item.id")).append(' ');
		boolean own = inside.toString().contains("dirt") && inside.toString().contains("stone");
		boolean hotbarOk = hotbarKept(t);
		List<String> sc = SortScenes.screens(out);
		boolean ok = standing == 3 && took != null && own && hotbarOk && !sc.isEmpty() && sc.getLast().equals("none");
		String detail = standing + " standing, " + (took != null ? String.format(Locale.ROOT, "all back in %.1f s", took) : "not all back")
			+ (own ? ", what was in them kept" : ", contents " + inside.toString().trim()) + (hotbarOk ? "" : ", hotbar not as it was") + ", screens " + sc;
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "boxes")
	public void enderChestNearby(TestContext t) {
		// An ender chest in reach is a tab on the swap bar, over your inventory too: its tab opens it and its diamonds come
		// out. With a solid block on it (it wouldn't open) there's no tab.
		start(t);
		String name = t.player().name();
		t.arena().set(2, 0, 0, "ender_chest[facing=west]").set(2, 1, 0, "air");
		t.server().run("item replace entity " + name + " enderchest.0 with diamond 7");
		t.player().give(3, "stick", 5);
		t.module(SortScenes.INVENTORY_TWEAKS).set("remote_ender_chest", "false").enable();
		List<String> out = SortScenes.script(t, "wait 5", "key e", "wait 10", "tabs", "tab 1", "wait 20", "status",
			"cursor slot 0", "mouse 0 click", "cursor slot 54", "mouse 0 click", "wait 10", "key escape", "wait 10");
		boolean taken = t.server().passes("if items entity " + name + " hotbar.0 diamond") && !t.server().passes("if items entity " + name + " enderchest.0 diamond");
		t.arena().set(2, 1, 0, "stone");
		List<String> out2 = SortScenes.script(t, "key e", "wait 10", "tabs", "key escape", "wait 5");
		List<String> tabs = SortScenes.tabs(out), blocked = SortScenes.tabs(out2), sc = SortScenes.screens(out);
		String pos = SortScenes.tabPos(t, 2, 0, 0);
		boolean ok = !tabs.isEmpty() && tabs.getFirst().contains(pos) && first(sc).equals(List.of("ContainerScreen")) && taken
			&& !blocked.isEmpty() && !blocked.getFirst().contains(pos);
		String detail = "tabs " + first(tabs) + ", screens " + first(sc) + ", " + (taken ? "diamonds taken" : "diamonds not taken") + ", under a block " + first(blocked);
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "boxes")
	public void fullInventory(TestContext t) {
		// Four shulker boxes put down from a chest with two empty slots in your inventory: Escape puts them back two at a time
		// (all are picked up before any goes back, so no more than fit), all back in the chest and none left on the ground.
		start(t);
		List<String> shulkers = List.of("red_shulker_box", "blue_shulker_box", "green_shulker_box", "yellow_shulker_box");
		t.arena().set(2, 0, 0, "chest[facing=west]");
		for (int i = 0; i < shulkers.size(); i++) t.server().run("item replace block " + t.arena().block(2, 0, 0) + " container." + i + " with " + shulkers.get(i));
		for (int s = 0; s < 36; s++) {
			if (s == 2 || s == 20) continue;
			if (s == 6) t.player().give(s, "diamond_pickaxe");
			else t.player().give(s, "dirt", 64);
		}
		tweaks(t);
		t.player().select(2);
		List<String> steps = new ArrayList<>(List.of("wait 10", "use", "wait 10"));
		for (int i = 0; i < 4; i++) steps.addAll(List.of("cursor slot " + i, "mouse 1 click alt", "wait 25"));
		steps.addAll(List.of("status", "tabs"));
		List<String> out = new ArrayList<>(SortScenes.script(t, steps.toArray(String[]::new)));
		int standing = standing(t, new int[]{-1, 0, 1}, 2);
		out.addAll(SortScenes.script(t, "key escape", "wait 400", "status"));
		int back = boxesInChest(t);
		boolean lying = t.server().passes("if entity @e[type=item,x=" + t.arena().x(0) + ",y=" + t.arena().y(0) + ",z=" + t.arena().z(0) + ",distance=..8]");
		Matcher dirt = COUNT.matcher(t.server().run("execute if items entity " + t.player().name() + " container.* dirt"));
		boolean dirtOk = dirt.find() && Integer.parseInt(dirt.group(1)) == 33 * 64;
		List<String> sc = SortScenes.screens(out);
		boolean ok = standing == 4 && back == 4 && !lying && dirtOk && !sc.isEmpty() && sc.getLast().equals("none");
		String detail = standing + " standing, " + back + " back in the chest" + (lying ? ", SOME LEFT ON THE GROUND" : "") + (dirtOk ? "" : ", the inventory changed")
			+ ", screens " + sc;
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "boxes")
	public void fullHotbar(TestContext t) {
		// Two shulker boxes put down from a chest with a full hotbar: what made room for each waits in your inventory, and is
		// back in the hotbar once both boxes are back in the chest (one box lands in the other's hotbar slot meanwhile).
		start(t);
		t.arena().set(2, 0, 0, "chest[facing=west]");
		t.server().run("item replace block " + t.arena().block(2, 0, 0) + " container.0 with red_shulker_box");
		t.server().run("item replace block " + t.arena().block(2, 0, 0) + " container.1 with blue_shulker_box");
		for (int h = 0; h < 9; h++) {
			if (h == 6) t.player().give(h, "diamond_pickaxe");
			else t.player().give(h, "oak_planks", h + 1);
		}
		tweaks(t);
		t.player().select(2);
		List<String> out = SortScenes.script(t, "wait 10", "use", "wait 10",
			"cursor slot 0", "mouse 1 click alt", "wait 25", "cursor slot 1", "mouse 1 click alt", "wait 25", "key escape", "wait 300", "status");
		int back = boxesInChest(t);
		List<Integer> wrong = new ArrayList<>();
		for (int h = 0; h < 9; h++) {
			String want = h == 6 ? "minecraft:diamond_pickaxe" : "minecraft:oak_planks";
			if (!t.player().item(h).equals(want) || (h != 6 && t.player().count(h) != h + 1)) wrong.add(h);
		}
		List<String> sc = SortScenes.screens(out);
		boolean ok = back == 2 && wrong.isEmpty() && !sc.isEmpty() && sc.getLast().equals("none");
		String detail = back + " back in the chest" + (wrong.isEmpty() ? ", hotbar as it was" : ", hotbar " + wrong + " not as it was");
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "boxes")
	public void chestOutOfReach(TestContext t) {
		// A shulker box put down from a chest, then the tester moved so the chest is out of reach (as stepping to where boxes
		// fell does): Escape picks it up and walks back into reach to put it in the chest.
		start(t);
		t.arena().set(2, 0, 0, "chest[facing=west]");
		t.server().run("item replace block " + t.arena().block(2, 0, 0) + " container.4 with red_shulker_box");
		t.player().give(6, "diamond_pickaxe");
		tweaks(t);
		t.player().select(2);
		SortScenes.script(t, "wait 10", "use", "wait 10", "cursor slot 4", "mouse 1 click alt", "wait 30", "tabs");
		int[] box = null;
		for (int x = -1; x <= 1 && box == null; x++) for (int z = -1; z <= 1 && box == null; z++) if (t.arena().is(x, 0, z, "red_shulker_box")) box = new int[]{x, z};
		// Back from the box, away from the chest: the box still in reach, the chest not.
		if (box != null) t.player().at(box[0] - 3.5, 0, box[1] + 0.5, -90, 35);
		List<String> out = SortScenes.script(t, "status", "key escape", "wait 300", "status");
		List<String> walked = new ArrayList<>();
		for (String line : out) {
			Matcher m = STATUS_X.matcher(line);
			if (m.find()) walked.add(String.format(Locale.ROOT, "%.3f", Double.parseDouble(m.group(1)) - t.arena().x(0)));
		}
		boolean back = t.server().passes("if items block " + t.arena().block(2, 0, 0) + " container.* red_shulker_box");
		boolean yours = t.server().passes("if items entity " + t.player().name() + " container.* red_shulker_box");
		String at = box == null ? "None" : "(" + box[0] + ", " + box[1] + ")";
		String detail = "box at " + at + ", " + (back ? "back in the chest" : yours ? "left in your inventory" : "LOST") + ", x " + walked;
		t.check(box != null && back, detail);
		t.note(detail);
	}

	@Test(tags = "boxes")
	public void yourInventory(TestContext t) {
		// A shulker box opened from your inventory, then your inventory's tab: the box stands while it shows; Escape there puts
		// the box back in its slot.
		start(t);
		t.player().give(0, "diamond_sword").give(14, "red_shulker_box");
		tweaks(t);
		t.player().select(1);
		List<String> out = new ArrayList<>(SortScenes.script(t, "wait 10", "key e", "wait 10",
			"cursor slot 14", "mouse 1 click alt", "wait 30", "status", "tab 0", "wait 10", "status"));
		boolean standing = standing(t, new int[]{1, 0, -1}, 3) > 0;
		out.addAll(SortScenes.script(t, "key escape", "wait 200", "status"));
		List<String> sc = SortScenes.screens(out);
		boolean home = t.server().passes("if items entity " + t.player().name() + " container.* red_shulker_box");
		boolean ok = sc.equals(List.of("ShulkerBoxScreen", "InventoryScreen", "none")) && standing && home;
		String detail = "screens " + sc + (standing ? ", standing while your inventory showed" : ", not standing") + (home ? ", back in your inventory" : ", not back");
		t.check(ok, detail);
		t.note(detail);
	}
}
