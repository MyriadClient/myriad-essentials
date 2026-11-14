package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scenes Auto Sort, Inventory Tweaks' Middle Sort and the swap bar are tested in: a double chest of scattered part
 * stacks (and shulker boxes, each with its own contents), read back from the server slot by slot, and the helpers for
 * reading what the dev console's steps said (screens, the swap bar's tabs, the cursor).
 */
final class SortScenes {
	static final String AUTO_SORT = "myriad-essentials:auto_sort", INVENTORY_TWEAKS = "myriad-essentials:inventory_tweaks";

	/** The items the sort scenes are made of, with their stack sizes. */
	static final Map<String, Integer> SORT_ITEMS = new LinkedHashMap<>();

	static {
		for (String item : List.of("cobblestone", "dirt", "oak_planks", "sand", "glass", "iron_ingot")) SORT_ITEMS.put(item, 64);
		SORT_ITEMS.put("ender_pearl", 16);
		SORT_ITEMS.put("snowball", 16);
	}

	private static final List<String> SORT_ITEM_NAMES = List.copyOf(SORT_ITEMS.keySet());
	static final List<String> SHULKER_COLORS = List.of("red", "blue", "lime", "yellow", "purple", "black", "white", "cyan");

	private static final Pattern SCREEN = Pattern.compile("screen=(\\S+)");

	private SortScenes() {
	}

	/** A slot's stack: the item's id without "minecraft:", and how many. */
	record Stack(String item, int count) {
		@Override
		public String toString() {
			return item + " x" + count;
		}
	}

	/** How a {@link #sortPass} goes. */
	record Sort(boolean middle, boolean held, boolean fullInventory, int clickAfter) {
		/** Auto Sort sorts the chest as it's opened. */
		static final Sort AUTO = new Sort(false, false, false, -1);
		/** Auto Sort off: Inventory Tweaks' middle-click over a chest slot sorts it. */
		static final Sort MIDDLE = new Sort(true, false, false, -1);

		/** Opened with the right button held a moment (and the middle button held a few ticks), as by hand. */
		Sort openedByHand() {
			return new Sort(middle, true, fullInventory, clickAfter);
		}

		/** Your inventory full of torches. */
		Sort withFullInventory() {
			return new Sort(middle, held, true, clickAfter);
		}

		/** You click a chest slot {@code ticks} after opening it. */
		Sort clickingAfter(int ticks) {
			return new Sort(middle, held, fullInventory, ticks);
		}
	}

	/** What a {@link #shulkerSortPass} sorts. */
	enum ShulkerSort {
		/** A double chest of boxes and part stacks, with Auto Sort. */
		AUTO,
		/** The same with boxes in your inventory too, which are left alone. */
		AUTO_CARRY,
		/** The double chest by a middle-click. */
		MIDDLE,
		/** Your own inventory by a middle-click. */
		INVENTORY
	}

	/**
	 * Auto Sort on a double chest of scattered part stacks, opened by hand: merged, packed from the first slot, nothing lost
	 * and nothing left on the cursor or in your inventory. With {@link Sort#clickAfter}, you click a chest slot that many
	 * ticks after opening it, which stops the sort: what it held is put down, and nothing is lost. {@link Sort#middle}: Auto
	 * Sort off, sorted by Inventory Tweaks' middle-click over a chest slot instead. {@link Sort#held}: the chest opened with
	 * the right button held a moment and the middle button held a few ticks, as by hand: the releases come with the sort
	 * under way, and must not count as clicks.
	 */
	static void sortPass(TestContext t, Sort how) {
		Random rng = new Random(7);
		Map<String, Integer> totals = new TreeMap<>();
		// Facing west, the left half's other half is north of it.
		t.arena().set(2, 0, 0, "chest[facing=west,type=right]").set(2, 0, 1, "chest[facing=west,type=left]");
		for (int z = 0; z <= 1; z++) {
			for (int slot : sample(rng, 27, 20)) {
				String item = SORT_ITEM_NAMES.get(rng.nextInt(SORT_ITEM_NAMES.size()));
				int n = 1 + rng.nextInt(SORT_ITEMS.get(item) / 2);
				t.server().run("item replace block " + t.arena().block(2, 0, z) + " container." + slot + " with " + item + " " + n);
				totals.merge(item, n, Integer::sum);
			}
		}
		if (how.fullInventory()) for (int i = 0; i < 27; i++) t.player().give(9 + i, "torch", 64);
		t.player().at(0.5, 0, 1.0, -90, 25);
		if (!how.middle()) t.module(AUTO_SORT).enable();
		t.module(INVENTORY_TWEAKS).set("middle_sort", "true").enable();
		t.waitTicks(5);
		t.flagsFromHere();
		long start = System.nanoTime();
		if (how.held()) {
			t.step("mouse 1 press");
			t.waitTicks(12);
			t.step("mouse 1 release");
		} else {
			t.step("use");
		}
		if (how.clickAfter() >= 0) {
			t.waitTicks(how.clickAfter());
			// The second slot of the chest's top row, where the screenshots put it.
			t.step("cursor slot 1", "mouse 0 click");
		}
		if (how.middle()) {
			t.waitTicks(20);
			t.step("cursor slot 1");
			if (how.held()) {
				t.step("mouse 2 press");
				t.waitTicks(8);
				t.step("mouse 2 release");
			} else {
				t.step("mouse 2 click");
			}
		}
		// Sorted on the server: polled until it is (or 20 s).
		Double took = null;
		double limit = how.clickAfter() >= 0 ? 6 : 20;
		while (seconds(start) < limit) {
			if (doubleChestSorted(t)) {
				took = seconds(start);
				break;
			}
			t.waitTicks(5);
		}
		t.waitTicks(20);
		t.waitTicks(2);
		String carried = carried(t);
		t.module(AUTO_SORT).disable();
		t.waitTicks(2);
		Map<String, Integer> now = new TreeMap<>();
		List<Stack> chest = chestItems(t, 2, 0, 1);
		chest.addAll(chestItems(t, 2, 0, 0));
		for (Stack s : chest) if (s != null) now.merge(s.item(), s.count(), Integer::sum);
		// Parked in your inventory and not put back would show here (the torches of a full inventory aren't the chest's).
		for (Stack s : inventorySlots(t).values()) if (SORT_ITEMS.containsKey(s.item())) now.merge(s.item(), s.count(), Integer::sum);
		boolean kept = now.equals(totals);
		boolean sortedOk = took != null || how.clickAfter() >= 0;
		String text = took != null ? String.format(Locale.ROOT, "sorted in %.1f s", took) : how.clickAfter() >= 0 ? "stopped by the click" : "not sorted in 20 s";
		String detail = text + (kept ? ", nothing lost" : ", items changed: " + now + " vs " + totals) + ", cursor " + carried;
		t.check(sortedOk && kept && carried.equals("none"), detail);
		t.note(detail);
	}

	/** Whether the double chest is sorted and merged, read in either half's order. */
	private static boolean doubleChestSorted(TestContext t) {
		List<Stack> a = chestItems(t, 2, 0, 1), b = chestItems(t, 2, 0, 0);
		List<Stack> ab = new ArrayList<>(a), ba = new ArrayList<>(b);
		ab.addAll(b);
		ba.addAll(a);
		return sortedAndMerged(ab) || sortedAndMerged(ba);
	}

	/**
	 * Sorts with shulker boxes in the mix (they don't stack and each carries its contents, the heaviest updates there are):
	 * {@link ShulkerSort#AUTO} or {@link ShulkerSort#MIDDLE} sort a double chest of boxes and part stacks with Auto Sort or
	 * a middle-click, {@link ShulkerSort#AUTO_CARRY} the same with boxes in your inventory too (left alone),
	 * {@link ShulkerSort#INVENTORY} your own inventory by middle-click. Every box must come out with its own contents (n
	 * dirt each), nothing lost, the cursor empty.
	 */
	static void shulkerSortPass(TestContext t, ShulkerSort mode) {
		Random rng = new Random(23);
		List<Map.Entry<String, Integer>> boxes = new ArrayList<>();
		Map<String, Integer> totals = new TreeMap<>();
		List<String> setup = new ArrayList<>();
		String name = t.player().name();
		boolean chest = mode != ShulkerSort.INVENTORY;
		if (chest) {
			List<int[]> spots = new ArrayList<>();
			for (int z = 0; z <= 1; z++) for (int s = 0; s < 27; s++) spots.add(new int[]{z, s});
			Collections.shuffle(spots, rng);
			for (int k = 0; k < 16; k++) {
				int[] spot = spots.removeLast();
				String color = SHULKER_COLORS.get(rng.nextInt(SHULKER_COLORS.size()));
				boxes.add(Map.entry(color, k + 1));
				setup.add("item replace block " + t.arena().block(2, 0, spot[0]) + " container." + spot[1] + " with " + boxItem(color, k + 1));
				totals.merge(color + "_shulker_box", 1, Integer::sum);
			}
			for (int k = 0; k < 4; k++) {
				int[] spot = spots.removeLast();
				setup.add("item replace block " + t.arena().block(2, 0, spot[0]) + " container." + spot[1] + " with white_shulker_box");
				totals.merge("white_shulker_box", 1, Integer::sum);
			}
			for (int k = 0; k < 14; k++) {
				int[] spot = spots.removeLast();
				String item = SORT_ITEM_NAMES.get(rng.nextInt(SORT_ITEM_NAMES.size()));
				int n = 1 + rng.nextInt(SORT_ITEMS.get(item) / 2);
				setup.add("item replace block " + t.arena().block(2, 0, spot[0]) + " container." + spot[1] + " with " + item + " " + n);
				totals.merge(item, n, Integer::sum);
			}
		}
		List<Integer> carriedBoxes = new ArrayList<>();
		if (mode == ShulkerSort.AUTO_CARRY || mode == ShulkerSort.INVENTORY) {
			List<Integer> slots = sample(rng, 27, mode == ShulkerSort.INVENTORY ? 12 : 6);
			for (int k = 0; k < slots.size(); k++) {
				int slot = slots.get(k);
				if (k % 3 == 2 && mode == ShulkerSort.INVENTORY) {
					String item = SORT_ITEM_NAMES.get(rng.nextInt(SORT_ITEM_NAMES.size()));
					int n = 1 + rng.nextInt(SORT_ITEMS.get(item) / 2);
					setup.add("item replace entity " + name + " inventory." + slot + " with " + item + " " + n);
					totals.merge(item, n, Integer::sum);
					continue;
				}
				String color = SHULKER_COLORS.get(rng.nextInt(SHULKER_COLORS.size()));
				boxes.add(Map.entry(color, 40 + k));
				carriedBoxes.add(slot + 9);
				setup.add("item replace entity " + name + " inventory." + slot + " with " + boxItem(color, 40 + k));
				totals.merge(color + "_shulker_box", 1, Integer::sum);
			}
		}
		t.player().give(0, "diamond_sword");
		if (chest) t.arena().set(2, 0, 0, "chest[facing=west,type=right]").set(2, 0, 1, "chest[facing=west,type=left]");
		for (String command : setup) t.server().run(command);
		t.player().at(0.5, 0, 1.0, -90, 25);
		Map<Integer, Stack> invBefore = inventorySlots(t);
		if (mode == ShulkerSort.AUTO || mode == ShulkerSort.AUTO_CARRY) t.module(AUTO_SORT).enable();
		t.module(INVENTORY_TWEAKS).set("middle_sort", "true").enable();
		t.waitTicks(5);
		t.flagsFromHere();
		long start = System.nanoTime();
		if (chest) {
			t.step("use");
			if (mode == ShulkerSort.MIDDLE) {
				t.waitTicks(20);
				t.step("cursor slot 1", "mouse 2 click");
			}
		} else {
			t.step("key e");
			t.waitTicks(10);
			t.step("cursor slot 20", "mouse 2 click");
		}
		Double took = null;
		while (seconds(start) < 30) {
			if (chest ? shulkerChestSorted(t) : sortedAndMerged(inventoryRange(inventorySlots(t)))) {
				took = seconds(start);
				break;
			}
			t.waitTicks(10);
		}
		t.waitTicks(20);
		t.waitTicks(2);
		String cursor = carried(t);
		t.module(AUTO_SORT).disable();
		t.step("key escape");
		t.waitTicks(5);
		Map<String, Integer> now = new TreeMap<>();
		List<String> places = new ArrayList<>();
		if (chest) {
			for (int z = 0; z <= 1; z++) {
				for (Stack s : blockSlots(t, 2, 0, z, 27).values()) now.merge(s.item(), s.count(), Integer::sum);
				places.add("block " + t.arena().block(2, 0, z));
			}
		}
		Map<Integer, Stack> invAfter = inventorySlots(t);
		for (Map.Entry<Integer, Stack> e : invAfter.entrySet()) {
			if (totals.containsKey(e.getValue().item()) && e.getKey() != 0) now.merge(e.getValue().item(), e.getValue().count(), Integer::sum);
		}
		places.add("entity " + name);
		List<String> lost = new ArrayList<>();
		for (Map.Entry<String, Integer> box : boxes) {
			boolean found = false;
			for (String where : places) {
				if (boxWith(t, where, box.getKey(), box.getValue())) {
					found = true;
					break;
				}
			}
			if (!found) lost.add(box.getKey() + " " + box.getValue());
		}
		boolean kept = now.equals(totals);
		boolean untouched = mode != ShulkerSort.AUTO_CARRY || carriedBoxes.stream().allMatch(s -> Objects.equals(invBefore.get(s), invAfter.get(s)));
		boolean ok = took != null && kept && lost.isEmpty() && cursor.equals("none") && untouched;
		String detail = (took != null ? String.format(Locale.ROOT, "sorted in %.1f s", took) : "not sorted in 30 s")
			+ (kept ? ", nothing lost" : ", items changed: " + now + " vs " + totals)
			+ (lost.isEmpty() ? ", all " + boxes.size() + " boxes with their contents" : ", boxes not found as they were: " + lost)
			+ ", cursor " + cursor + (untouched ? "" : ", your boxes moved");
		t.check(ok, detail);
		t.note(detail);
	}

	/** Whether the double chest of a {@link #shulkerSortPass} is sorted and merged, read in either half's order. */
	private static boolean shulkerChestSorted(TestContext t) {
		Map<Integer, Stack> a = blockSlots(t, 2, 0, 1, 27), b = blockSlots(t, 2, 0, 0, 27);
		List<Stack> ab = new ArrayList<>(), ba = new ArrayList<>();
		for (int i = 0; i < 27; i++) ab.add(a.get(i));
		for (int i = 0; i < 27; i++) ab.add(b.get(i));
		for (int i = 0; i < 27; i++) ba.add(b.get(i));
		for (int i = 0; i < 27; i++) ba.add(a.get(i));
		return sortedAndMerged(ab) || sortedAndMerged(ba);
	}

	/** A box of {@code color} holding {@code n} dirt in its first slot and {@code n} iron in its sixth. */
	private static String boxItem(String color, int n) {
		return color + "_shulker_box[container=[{slot:0,item:{id:\"dirt\",count:" + n + "}},{slot:5,item:{id:\"iron_ingot\",count:" + n + "}}]]";
	}

	/** {@code n} different numbers from 0 to {@code range - 1}, in random order. */
	private static List<Integer> sample(Random rng, int range, int n) {
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < range; i++) all.add(i);
		Collections.shuffle(all, rng);
		return all.subList(0, n);
	}

	private static double seconds(long since) {
		return (System.nanoTime() - since) / 1e9;
	}

	/** A chest's slots on the server ({@code null} for an empty one), from the API's reading of its items. */
	static List<Stack> chestItems(TestContext t, int x, int y, int z) {
		List<Stack> out = new ArrayList<>();
		for (String s : t.arena().items(x, y, z, 27)) {
			int at = s.lastIndexOf(" x");
			out.add(s.isEmpty() || at < 0 ? null : new Stack(s.substring(0, at), Integer.parseInt(s.substring(at + 2))));
		}
		return out;
	}

	/** A block's items by slot (the first {@code n}), from the server. */
	static Map<Integer, Stack> blockSlots(TestContext t, int x, int y, int z, int n) {
		return slots(t.arena().items(x, y, z, n));
	}

	/** The player's inventory by slot as the API numbers them (0-8 hotbar, 9-35 inventory, 36-39 armour, 40 off hand). */
	static Map<Integer, Stack> inventorySlots(TestContext t) {
		return slots(t.player().inventory());
	}

	/** The API's {@code "dirt x64"} slots as stacks, by slot, empty ones left out. */
	private static Map<Integer, Stack> slots(List<String> read) {
		Map<Integer, Stack> found = new HashMap<>();
		for (int i = 0; i < read.size(); i++) {
			String s = read.get(i);
			int at = s.lastIndexOf(" x");
			if (!s.isEmpty() && at > 0) found.put(i, new Stack(s.substring(0, at), Integer.parseInt(s.substring(at + 2))));
		}
		return found;
	}

	/** Slots 9 to 35 of an {@link #inventorySlots} reading, in order ({@code null} for an empty one). */
	static List<Stack> inventoryRange(Map<Integer, Stack> slots) {
		List<Stack> out = new ArrayList<>();
		for (int i = 9; i < 36; i++) out.add(slots.get(i));
		return out;
	}

	/** Whether a {@code color} shulker box holding exactly {@code n} dirt in its first slot is in {@code where} ("block x y z" or "entity name"). */
	static boolean boxWith(TestContext t, String where, String color, int n) {
		String match = "{id:\"minecraft:" + color + "_shulker_box\",components:{\"minecraft:container\":[{slot:0,item:{id:\"minecraft:dirt\",count:" + n + "}}]}}";
		return t.server().passes("if data " + where + " " + (where.startsWith("block") ? "Items" : "Inventory") + "[" + match + "]");
	}

	/** Whether a run of slots (each a stack or {@code null}) is packed from the first and every item fills its stacks. */
	static boolean sortedAndMerged(List<Stack> slots) {
		List<Stack> used = slots.stream().filter(Objects::nonNull).toList();
		for (int i = 0; i < used.size(); i++) if (slots.get(i) == null) return false;
		Map<String, List<Integer>> kinds = new HashMap<>();
		for (Stack s : used) kinds.computeIfAbsent(s.item(), k -> new ArrayList<>()).add(s.count());
		for (Map.Entry<String, List<Integer>> e : kinds.entrySet()) {
			int limit = stackLimit(e.getKey());
			if (e.getValue().stream().filter(n -> n < limit).count() > 1) return false;
		}
		return true;
	}

	static int stackLimit(String item) {
		return item.endsWith("shulker_box") ? 1 : SORT_ITEMS.getOrDefault(item, 64);
	}

	/**
	 * Runs the dev console's steps in order, {@code "wait N"} as a wait of N ticks, and returns every line they said (for
	 * {@link #screens} and {@link #tabs}).
	 */
	static List<String> script(TestContext t, String... steps) {
		List<String> said = new ArrayList<>();
		for (String step : steps) {
			if (step.startsWith("wait ")) t.waitTicks(Integer.parseInt(step.substring(5)));
			else said.addAll(t.step(step));
		}
		return said;
	}

	/** The screens the {@code status} lines in {@code out} saw, in order. */
	static List<String> screens(List<String> out) {
		List<String> found = new ArrayList<>();
		for (String line : out) {
			Matcher m = SCREEN.matcher(line);
			if (line.startsWith("status:") && m.find()) found.add(m.group(1));
		}
		return found;
	}

	/** The swap bar's tabs as each {@code tabs} line in {@code out} listed them. */
	static List<String> tabs(List<String> out) {
		List<String> found = new ArrayList<>();
		for (String line : out) if (line.startsWith("tabs: ")) found.add(line.substring(6));
		return found;
	}

	/** What the cursor carries ("none" if nothing), as the dev console's status says. */
	static String carried(TestContext t) {
		return t.onGame(() -> {
			ItemStack c = Minecraft.getInstance().player.containerMenu.getCarried();
			return c.isEmpty() ? "none" : c.getCount() + " " + BuiltInRegistries.ITEM.getKey(c.getItem()).getPath();
		});
	}

	/** The arena's block position as the swap bar's tabs print it ({@code "30002, 100, 0"}). */
	static String tabPos(TestContext t, int x, int y, int z) {
		return t.arena().x(x) + ", " + t.arena().y(y) + ", " + t.arena().z(z);
	}

	/** The slots of a chest that aren't empty, for a result line. */
	static Map<Integer, Stack> filled(List<Stack> slots) {
		Map<Integer, Stack> out = new TreeMap<>();
		for (int i = 0; i < slots.size(); i++) if (slots.get(i) != null) out.put(i, slots.get(i));
		return out;
	}

}
