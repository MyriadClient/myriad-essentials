package dev.myriadclient.essentials.gametest;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static dev.myriadclient.essentials.gametest.BoxScenes.SILK;
import static dev.myriadclient.essentials.gametest.BoxScenes.give;
import static dev.myriadclient.essentials.gametest.BoxScenes.holds;
import static dev.myriadclient.essentials.gametest.BoxScenes.id;
import static dev.myriadclient.essentials.gametest.BoxScenes.idAt;
import static dev.myriadclient.essentials.gametest.BoxScenes.screen;
import static dev.myriadclient.essentials.gametest.BoxScenes.slot;

/**
 * Boxes opened from your inventory (Containers.openItem, as Inventory Tweaks' Mod + right-click does): placed above you
 * or beside you, opened, then broken, picked up and put back; from a chest you have open, put down and opened from
 * their tab on the swap bar, and back in the chest on Escape. An ender chest is only ever broken with Silk Touch.
 */
public final class OpenBoxTests extends EssentialsSuite {
	/** The inventory the plain box passes start from, by slot (0-8 hotbar, 9-35 inventory). */
	private static final Map<Integer, String> BOX_ITEMS = new TreeMap<>(Map.of(0, "diamond_sword", 1, "cobblestone 32", 3, "red_shulker_box",
		5, "ender_chest 4", 6, SILK, 14, "blue_shulker_box"));

	/** Whether it passed, and the result line. */
	private record Result(boolean ok, String detail) {
	}

	/** What came of a box opened through {@link #openItem}, filled in on the game's thread as it happens. */
	private static final class Opening {
		volatile BlockPos where;
		volatile long millis;
		volatile String failed;
		/** Null until it has gone back; "" if that went well, else why not. */
		volatile String back;
	}

	/**
	 * Opens the box at inventory index {@code index} as core's {@code openitem} console step does (Containers.openItem
	 * with the server's placement rules, no air placement, 100 ticks to open) and puts it back {@code keepTicks} ticks
	 * after it opened. The step reports where it opened and when it went back only to the game's log, after the step
	 * itself has completed, so the test makes the same calls and reads the outcome from them instead.
	 */
	private static Opening openItem(TestContext t, int index, int keepTicks) {
		Opening o = new Opening();
		Object owner = new Object() {
			@Override
			public String toString() {
				return "Open Box test";
			}
		};
		t.onGame(() -> {
			long started = System.currentTimeMillis();
			Myriad.containers().openItem(owner, index, 100, Placement.Options.forServer().withAirPlace(false)).whenComplete((session, e) -> {
				if (e != null) {
					o.failed = reason(e);
					return;
				}
				o.millis = System.currentTimeMillis() - started;
				o.where = session.where();
				Myriad.jobs().later(owner, keepTicks, () -> session.finish().whenComplete((v, e2) -> o.back = e2 == null ? "" : reason(e2)));
			});
		});
		return o;
	}

	private static String reason(Throwable e) {
		return e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
	}

	/**
	 * The box at {@code index} opened from the inventory {@code items} with hotbar slot {@code held} selected: placed
	 * above the tester under a ceiling (a stone layer 4 up), else beside them, opened, then broken, picked up and put
	 * back. {@code closeAt} (above 0): the tester closes the screen that many ticks in, as you would, and the hotbar is
	 * looked at before the box goes back. The box itself is back anywhere in the inventory (it stays where it lands);
	 * everything else as it was.
	 */
	private static Result boxPass(TestContext t, int index, boolean ceiling, Map<Integer, String> items, int held, boolean replenish, int closeAt) {
		give(t, items);
		if (ceiling) t.arena().fill(-3, 4, -3, 3, 4, 3, "stone");
		if (replenish) t.module("myriad-essentials:replenish").enable();
		t.player().select(held);
		t.waitTicks(10);
		Opening o;
		List<Integer> stray = new ArrayList<>();
		if (closeAt > 0) {
			o = openItem(t, index, 60);
			t.waitTicks(closeAt);
			t.step("closescreen");
			t.waitTicks(5);
			for (int h = 0; h < 9; h++) if (!items.containsKey(h) && t.player().count(h) > 0) stray.add(h);
			t.waitTicks(200);
		} else {
			o = openItem(t, index, 20);
			t.waitTicks(160);
		}
		// By the server's item test: a long entry (the pickaxe's enchantment and wear) comes back cut short of its id.
		String name = id(items.get(index));
		Set<Integer> wrong = new TreeSet<>();
		for (Map.Entry<Integer, String> e : items.entrySet()) {
			int s = e.getKey();
			if (s == index) continue;
			if (!holds(t, slot(s), id(e.getValue()))) wrong.add(s);
			if (e.getValue().startsWith("ender_chest") && t.player().count(s) != BoxScenes.amount(e.getValue())) wrong.add(s);
		}
		if (!holds(t, "container.*", name)) wrong.add(index);
		// A block up from right over the tester's head, under the ceiling (a shulker box's lid right over it would push them).
		BlockPos where = o.where;
		boolean above = where != null && where.getY() == t.arena().y(3);
		boolean ok = where != null && o.back != null && wrong.isEmpty() && stray.isEmpty() && above == ceiling;
		String opened = where != null ? "opened " + (above ? "above" : "beside") + " the tester after " + o.millis + " ms"
			: "didn't open (" + (o.failed != null ? o.failed : "no answer") + ")";
		return new Result(ok, opened + (wrong.isEmpty() ? ", put back" : ", slots " + wrong + " not as they were")
			+ (stray.isEmpty() ? "" : ", hotbar " + stray + " filled once closed"));
	}

	private static Result boxPass(TestContext t, int index, boolean ceiling) {
		return boxPass(t, index, ceiling, BOX_ITEMS, 1, false, 0);
	}

	private static void report(TestContext t, Result r) {
		t.check(r.ok, r.detail);
		t.note(r.detail);
	}

	@Test(tags = "boxes")
	public void fromTheHotbar(TestContext t) {
		report(t, boxPass(t, 3, false));
	}

	@Test(tags = "boxes")
	public void enderChestFromAStack(TestContext t) {
		report(t, boxPass(t, 5, false));
	}

	@Test(tags = "boxes")
	public void fromTheInventoryAboveYou(TestContext t) {
		report(t, boxPass(t, 14, true));
	}

	@Test(tags = "boxes")
	public void enderChestSilkTouchInTheInventory(TestContext t) {
		// Holding a plain pickaxe, the Silk Touch one in the main inventory: the chest is broken with that one, not obsidian.
		Map<Integer, String> items = new TreeMap<>(BOX_ITEMS);
		items.put(6, "diamond_pickaxe");
		items.put(20, SILK);
		Result r = boxPass(t, 5, false, items, 6, false, 0);
		boolean silk = holds(t, "inventory.11", "diamond_pickaxe[enchantments~[{enchantments:'silk_touch'}]]");
		report(t, new Result(r.ok && silk, r.detail + (silk ? "" : ", the Silk Touch pickaxe isn't back in its slot")));
	}

	@Test(tags = "boxes")
	public void enderChestFromTheInventoryWithReplenish(TestContext t) {
		// A stack from the main inventory, given back to its slot through the chest's screen: Replenish, on, mustn't take
		// the hotbar slot it was pulled into for one you emptied and fill it again when you close the screen.
		Map<Integer, String> items = new TreeMap<>(BOX_ITEMS);
		items.remove(5);
		items.put(14, "ender_chest 4");
		report(t, boxPass(t, 14, false, items, 1, true, 30));
	}

	/**
	 * Inventory Tweaks' Mod + right-click on a box in a chest opened by hand ({@code inChest}: one of the chest's slots,
	 * else one of yours below them): it's only put down, the chest staying up, and opens from its tab on the swap bar;
	 * Escape puts everything back and leaves nothing open. {@code fullHotbar}: no free hotbar slot, so what makes room for
	 * it waits in an empty inventory slot meanwhile. {@code hotbar}/{@code extra}: other hotbar layouts and inventory
	 * items (null: the usual hotbar, no extra items). {@code times}: opened and closed that many times over, the later
	 * ones at the spot just broken (a finish alone breaks it there). Never any obsidian (an ender chest broken without
	 * Silk Touch). The chest stands at (2, 0, 0), facing west.
	 */
	private static Result chestBoxPass(TestContext t, String item, boolean inChest, boolean fullHotbar, Map<Integer, String> hotbar,
		Map<Integer, String> extra, int times) {
		t.player().at(0.5, 0, 0.5, -90, 35);
		Map<Integer, String> bar = new TreeMap<>(hotbar != null ? hotbar : Map.of(0, "diamond_sword", 1, "cobblestone 32", 6, SILK));
		if (fullHotbar) for (int i = 0; i < 9; i++) bar.putIfAbsent(i, "oak_planks " + (i + 1));
		Map<Integer, String> more = extra != null ? new TreeMap<>(extra) : Map.of();
		int chest = 4, mine = 14;
		t.arena().set(2, 0, 0, "chest[facing=west]");
		give(t, bar);
		if (inChest) t.server().run("item replace block " + t.arena().block(2, 0, 0) + " container." + chest + " with " + item);
		else give(t, Map.of(mine, item));
		give(t, more);
		String name = id(item);
		t.module("myriad-essentials:inventory_tweaks").set("air_place", "false").enable();
		t.player().select(1);
		t.waitTicks(10);
		t.step("use");
		t.waitTicks(10);
		for (int k = 1; k < times; k++) {
			t.step("cursor slot " + (inChest ? chest : 27 + mine - 9), "mouse 1 click alt");
			t.waitTicks(30);
			t.step("key escape");
			t.waitTicks(200);
			t.step("use");
			t.waitTicks(15);
			// Back in the chest's first free slot (any will do): clicked there next.
			List<String> in = t.arena().items(2, 0, 0, 27);
			for (int i = 0; i < in.size(); i++) {
				if (idAt(in, i).equals(name)) {
					chest = i;
					break;
				}
			}
		}
		t.step("cursor slot " + (inChest ? chest : 27 + mine - 9), "mouse 1 click alt");
		t.waitTicks(30);
		List<String> screens = new ArrayList<>();
		screens.add(screen(t));
		t.step("tab 2");
		t.waitTicks(20);
		String tabs = String.join(" ", t.step("tabs"));
		boolean outOfChest = inChest ? !idAt(t.arena().items(2, 0, 0, 27), chest).equals(name) : !holds(t, "inventory." + (mine - 9), name);
		// Escape, as you'd close it: everything put back, and nothing left open.
		screens.add(screen(t));
		t.step("key escape");
		t.waitTicks(200);
		screens.add(screen(t));
		// Back where it came from, in any slot: the chest, or your inventory.
		boolean back = inChest ? t.server().passes("if items block " + t.arena().block(2, 0, 0) + " container.* " + name) : holds(t, "container.*", name);
		List<Integer> wrong = new ArrayList<>();
		for (Map.Entry<Integer, String> e : bar.entrySet()) if (!holds(t, slot(e.getKey()), id(e.getValue()))) wrong.add(e.getKey());
		for (int s = 0; s < 9; s++) {
			if (!bar.containsKey(s) && t.player().count(s) > 0 && !(!inChest && holds(t, "hotbar." + s, name))) wrong.add(s);
		}
		for (Map.Entry<Integer, String> e : more.entrySet()) if (!holds(t, slot(e.getKey()), id(e.getValue()))) wrong.add(e.getKey());
		boolean obsidian = holds(t, "container.*", "obsidian") || t.arena().items(2, 0, 0, 27).stream().anyMatch(s -> s.startsWith("obsidian"));
		boolean closed = screens.get(2).equals("none");
		// Put down with the chest still up, then the box's tab current.
		boolean stayed = screens.get(0).equals("ContainerScreen");
		// The box's tab current (an ender chest's is the held one's, as Remote Ender Chest holds it): a position, not the chest's.
		String chestPos = t.arena().x(2) + ", " + t.arena().y(0) + ", " + t.arena().z(0);
		boolean opened = Pattern.compile("tabs: .*\\*(?!" + Pattern.quote(chestPos) + "\\b)-?\\d+, -?\\d+, -?\\d+").matcher(tabs).find();
		boolean ok = outOfChest && stayed && opened && back && wrong.isEmpty() && closed && !obsidian;
		return new Result(ok, (outOfChest ? "put down, the chest " + (stayed ? "still up" : "gone") + ", " + (opened ? "opened from its tab" : "its tab didn't open it")
			: "never left its slot") + (back ? ", back" : ", not back") + (wrong.isEmpty() ? "" : ", hotbar " + wrong + " not as it was")
			+ (closed ? ", all closed" : ", screens " + screens) + (obsidian ? ", OBSIDIAN: broken without Silk Touch" : ""));
	}

	@Test(tags = "boxes")
	public void inAChest(TestContext t) {
		report(t, chestBoxPass(t, "red_shulker_box", true, false, null, null, 1));
	}

	@Test(tags = "boxes")
	public void inAChestFullHotbar(TestContext t) {
		report(t, chestBoxPass(t, "red_shulker_box", true, true, null, null, 1));
	}

	@Test(tags = "boxes")
	public void enderChestInAChest(TestContext t) {
		report(t, chestBoxPass(t, "ender_chest 4", true, false, null, null, 1));
	}

	@Test(tags = "boxes")
	public void yoursWithAChestOpen(TestContext t) {
		report(t, chestBoxPass(t, "red_shulker_box", false, false, null, null, 1));
	}

	@Test(tags = "boxes")
	public void enderChestInAChestFullHotbar(TestContext t) {
		// The only Silk Touch pickaxe is in the full hotbar: something else makes room, so it's there for the break.
		report(t, chestBoxPass(t, "ender_chest 4", true, true, Map.of(0, SILK, 1, "cobblestone 32"), null, 1));
	}

	@Test(tags = "boxes", timeoutTicks = 2000)
	public void enderChestInAChestTwice(TestContext t) {
		// Twice at the same spot, the Silk Touch pickaxe in the main inventory and a plain one in the hotbar: the second
		// break is a finish alone, which mustn't go before the Silk Touch pickaxe is in hand.
		report(t, chestBoxPass(t, "ender_chest 4", true, false, Map.of(0, "diamond_sword", 6, "diamond_pickaxe"), Map.of(20, SILK), 2));
	}

	/**
	 * A shulker box Mod + right-clicked in an ender chest Inventory Tweaks put down and opened (from your inventory, with
	 * nothing on the swap bar): it's put down and opened from its tab, and Escape puts it back in the ender chest and the
	 * ender chest back in your hotbar. No obsidian.
	 */
	@Test(tags = "boxes", timeoutTicks = 2000)
	public void fromAPlacedBox(TestContext t) {
		t.player().at(0.5, 0, 0.5, -90, 35);
		give(t, Map.of(0, "diamond_sword", 5, "ender_chest 4", 6, SILK));
		BoxScenes.enderChest(t, 0, "red_shulker_box");
		BoxScenes.enderChest(t, 1, "bread 7");
		t.module("myriad-essentials:inventory_tweaks").set("air_place", "false").enable();
		t.player().select(1);
		t.waitTicks(10);
		t.step("key e");
		t.waitTicks(10);
		// The ender chest in hotbar slot 5 (slot 41 of your inventory's screen), then the shulker box in its first slot.
		t.step("cursor slot 41", "mouse 1 click alt");
		t.waitTicks(30);
		t.step("cursor slot 0", "mouse 1 click alt");
		t.waitTicks(30);
		t.step("tab 2");
		t.waitTicks(20);
		boolean nested = !holds(t, "enderchest.0", "red_shulker_box");
		// One Escape: the shulker box back into the ender chest (opened again for it), then the ender chest home.
		List<String> screens = new ArrayList<>();
		screens.add(screen(t));
		t.step("key escape");
		t.waitTicks(400);
		screens.add(screen(t));
		boolean backInside = holds(t, "enderchest.0", "red_shulker_box");
		boolean home = t.player().count(5) == 4 && holds(t, "hotbar.5", "ender_chest");
		boolean obsidian = holds(t, "container.*", "obsidian");
		boolean closed = screens.get(1).equals("none");
		boolean ok = nested && backInside && closed && home && !obsidian;
		report(t, new Result(ok, (nested ? "opened from the ender chest" : "the shulker box never left the ender chest")
			+ (backInside ? ", back inside it" : ", not back inside it") + (closed ? ", all closed" : ", screens " + screens)
			+ (home ? ", the ender chest home" : ", the ender chest not back in hotbar 5") + (obsidian ? ", OBSIDIAN" : "")));
	}

	/** A plain right-click (no Mod) on a stack of ender chests is vanilla's: half the stack picked up, nothing put down. */
	@Test(tags = "boxes")
	public void plainRightClick(TestContext t) {
		t.player().at(0.5, 0, 0.5, -90, 35);
		t.player().give(14, "ender_chest", 4).give(6, SILK);
		t.module("myriad-essentials:inventory_tweaks").set("air_place", "false").enable();
		t.waitTicks(5);
		t.step("key e");
		t.waitTicks(10);
		t.step("cursor slot 14", "mouse 1 click");
		t.waitTicks(20);
		t.step("cursor slot 15", "mouse 0 click");
		t.waitTicks(10);
		boolean split = t.player().count(14) == 2 && t.player().count(15) == 2;
		boolean standing = BoxScenes.any(t, -1, 0, -1, 1, 3, 1, "ender_chest");
		report(t, new Result(split && !standing, (split ? "split in two" : "not split") + (standing ? ", PUT DOWN" : ", nothing put down")));
	}

	/**
	 * A box put down from a chest whose slot is filled meanwhile (dirt put there): Escape puts it into the chest's first
	 * free slot, the dirt left where it is. With the chest full, it goes into your inventory.
	 */
	@Test(tags = "boxes", timeoutTicks = 2400)
	public void slotTaken(TestContext t) {
		List<Result> out = new ArrayList<>();
		String chest = t.arena().block(2, 0, 0);
		for (boolean full : new boolean[] {false, true}) {
			if (full) {
				// The second round starts over, as every test does (the runner set up the first).
				t.module("myriad-essentials:inventory_tweaks").disable();
				t.step("closescreen");
				t.arena().reset();
				t.player().fresh();
			}
			t.player().at(0.5, 0, 0.5, -90, 35);
			t.arena().set(2, 0, 0, "chest[facing=west]");
			t.player().give(0, "diamond_sword").give(6, "diamond_pickaxe");
			t.server().run("item replace block " + chest + " container.4 with red_shulker_box");
			t.module("myriad-essentials:inventory_tweaks").set("air_place", "false").enable();
			t.player().select(1);
			t.waitTicks(10);
			t.step("use");
			t.waitTicks(10);
			t.step("cursor slot 4", "mouse 1 click alt");
			t.waitTicks(30);
			if (full) for (int i = 0; i < 27; i++) t.server().run("item replace block " + chest + " container." + i + " with dirt");
			else t.server().run("item replace block " + chest + " container.4 with dirt");
			t.waitTicks(10);
			t.step("key escape");
			t.waitTicks(200);
			boolean dirt = t.server().passes("if items block " + chest + " container.4 dirt");
			boolean boxInChest = t.server().passes("if items block " + chest + " container.* red_shulker_box");
			boolean boxYours = holds(t, "container.*", "red_shulker_box");
			boolean standing = BoxScenes.any(t, -1, 0, -1, 1, 3, 1, "#minecraft:shulker_boxes");
			boolean ok = dirt && !standing && (full ? boxYours && !boxInChest : boxInChest && !boxYours);
			out.add(new Result(ok, (full ? "full chest: " : "slot taken: ")
				+ (boxYours ? "box in your inventory" : boxInChest ? "box in the chest" : !standing ? "box LOST" : "box standing") + (dirt ? "" : ", the dirt moved")));
		}
		report(t, new Result(out.stream().allMatch(Result::ok), String.join("; ", out.stream().map(Result::detail).toList())));
	}
}
