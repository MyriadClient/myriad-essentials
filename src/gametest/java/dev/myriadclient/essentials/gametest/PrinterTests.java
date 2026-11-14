package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The Printer: a row of blocks built with server commands (the reference) is saved as a Litematica schematic and placed
 * {@link #DZ} away, and the Printer builds it while you walk, sprint or jump past; the server then compares the two
 * rows block for block, and reads the hotbar and off hand, which must be as they were. The row is built 7000 blocks out
 * along x, away from the arena.
 */
public final class PrinterTests extends EssentialsSuite {
	private static final int X = 7000, Y = 0, Z = 0, DZ = 8;
	// Every block here can be placed from the row's own floor and neighbours (a log along z would need a block north or
	// south of it to be clicked; this row has none).
	private static final List<String> ROW;
	static {
		List<String> once = List.of("stone", "oak_log[axis=x]", "oak_stairs[facing=north]", "stone_brick_slab[type=top]", "cobblestone", "birch_planks",
			"stone_stairs[facing=south]", "smooth_stone_slab[type=bottom]", "glass", "furnace[facing=north]", "spruce_log[axis=y]", "brick_stairs[facing=east]");
		List<String> row = new ArrayList<>(once);
		row.addAll(once);
		ROW = List.copyOf(row);
	}
	/** The row's blocks as items, in name order: the inventory holds 16 of each from its first slot on. */
	private static final List<String> ITEMS = List.copyOf(new TreeSet<>(ROW.stream().map(PrinterTests::itemOf).toList()));
	private static final Map<Integer, String> HOTBAR = Map.of(0, "diamond_sword", 1, "diamond_pickaxe", 2, "golden_apple");
	private static final List<String> FULL_HOTBAR = List.of("diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "bow", "shield",
		"golden_apple", "totem_of_undying", "shears");

	/** How a pass goes; the defaults are a run past the row with Rotate on. */
	private static final class Pass {
		Key[] keys = {};
		boolean rotate = true, air, lenient;
		/** The x (from the row's start) to stand at in turn instead of moving; null to move. */
		int[] stand;
		int stopTicks = 40;
		Map<Integer, String> hotbar = HOTBAR;
		/** Printer settings beyond the usual ones, in order. */
		Map<String, String> settings = new LinkedHashMap<>();
		/** Items left out of the inventory. */
		Set<String> omit = Set.of();
		/** Blocks in Skip, whose places must stay air. */
		Set<String> leftOut = Set.of();
		/** More of the scene, set up before the teleport to the start. */
		Consumer<TestContext> setup = t -> {
		};
		double startX = -4.5, startZ = -1.5;
		int ticks = 140;

		Pass keys(Key... keys) {
			this.keys = keys;
			return this;
		}

		/** 2b2t's settings: Rotate off, Air Place on, the placements let through as 2b2t's Grim does. */
		Pass twoBTwoT() {
			rotate = false;
			air = true;
			lenient = true;
			return this;
		}

		Pass set(String key, String value) {
			settings.put(key, value);
			return this;
		}
	}

	/** A pass's outcome, with its summary line. */
	private record Result(boolean ok, String detail) {
	}

	private static String itemOf(String block) {
		int at = block.indexOf('[');
		return at < 0 ? block : block.substring(0, at);
	}

	private static Result printerPass(TestContext t, Pass p) {
		if (!t.hasMod("litematica")) t.skip("Litematica isn't in the dev client (start it with -Plitematica)");
		var a = t.arena();
		int n = ROW.size();
		t.step(".printscene clear");
		a.forceload(X - 32, Z - 8, X + n + 48, Z + DZ + 8);
		a.fill(X - 30, Y - 1, Z - 6, X + n + 44, Y - 1, Z + DZ + 2, "stone");
		a.fill(X - 30, Y, Z - 6, X + n + 44, Y + 6, Z + DZ + 2, "air");
		for (int i = 0; i < n; i++) a.set(X + i, Y, Z + DZ, ROW.get(i));
		t.server().run("kill @e[type=item,x=" + a.x(X) + ",y=" + a.y(Y) + ",z=" + a.z(Z) + ",distance=..60]");
		t.player().give(40, "totem_of_undying");
		p.hotbar.forEach((slot, item) -> t.player().give(slot, item));
		for (int k = 0; k < ITEMS.size(); k++) if (!p.omit.contains(ITEMS.get(k))) t.player().give(9 + k, ITEMS.get(k), 16);
		p.setup.accept(t);
		t.player().at(X + p.startX, Y, Z + p.startZ, -90, 20);
		// The reference row, as the client sees it, saved and placed DZ blocks nearer: the row the Printer builds. The
		// client has to have the row first (it's just arrived there): saved before, the schematic would be air.
		BlockPos first = new BlockPos(a.x(X), a.y(Y), a.z(Z + DZ)), last = new BlockPos(a.x(X + n - 1), a.y(Y), a.z(Z + DZ));
		if (!t.waitUntil("the client has the reference row", () -> {
			var level = Minecraft.getInstance().level;
			return level != null && !level.getBlockState(first).isAir() && !level.getBlockState(last).isAir();
		}, 200)) t.check(false, "the client never got the reference row");
		t.step(".printscene " + a.block(X, Y, Z + DZ) + " " + a.block(X + n - 1, Y, Z + DZ) + " 0 0 " + -DZ);
		t.waitTicks(2);
		t.check(t.chat().stream().anyMatch(l -> l.contains("Placed the schematic")), "Litematica placed the schematic");
		var printer = t.module("myriad-essentials:printer");
		printer.set("rotate", String.valueOf(p.rotate)).set("air_place", String.valueOf(p.air)).set("click_settings", "true")
			.set("break_wrong_blocks", "false").set("order", "bottom_up").set("skip", "clear").set("walk_to_blocks", "false").set("restock", "false");
		p.settings.forEach(printer::set);
		t.waitTicks(5);
		// 2b2t's Grim takes air placements and clicks on faces you don't look at; this one would cancel them. Let through
		// as there, they're only flagged and the game decides where blocks go.
		if (p.lenient) t.server().likeTwoBTwoT();
		t.flagsFromHere();
		try {
			printer.enable();
			if (p.stand == null) {
				// Past the row and a little beyond, then time for what's still in reach, then off: what it moved goes back.
				if (p.keys.length > 0) {
					t.hold(p.keys).forTicks(p.ticks);
					t.waitTicks(9);
				} else {
					t.waitTicks(p.ticks);
				}
				t.waitTicks(60);
			} else {
				// Standing still at each of these x in turn, long enough for everything in reach.
				for (int sx : p.stand) {
					t.player().at(X + sx + 0.5, Y, Z - 1.5, -90, 20);
					t.waitTicks(p.stopTicks);
				}
			}
			printer.disable();
			t.waitTicks(60);
		} finally {
			// Litematica keeps the placement otherwise, into the tests after this one.
			t.step(".printscene clear");
		}

		// Blocks left out on purpose (Skip) must stay air; the rest are judged as usual.
		List<Integer> skipped = new ArrayList<>(), placedSkipped = new ArrayList<>(), off = new ArrayList<>(), missing = new ArrayList<>();
		List<String> wrong = new ArrayList<>();
		for (int i = 0; i < n; i++) if (p.leftOut.contains(itemOf(ROW.get(i)))) skipped.add(i);
		for (int i : skipped) if (!a.is(X + i, Y, Z, "air")) placedSkipped.add(i);
		for (int i = 0; i < n; i++) {
			if (skipped.contains(i)) continue;
			String reference = a.block(X + i, Y, Z + DZ);
			if (!t.server().passes("if blocks " + reference + " " + reference + " " + a.block(X + i, Y, Z) + " all")) off.add(i);
		}
		n -= skipped.size();
		// Not placed (still air) is one thing, which a pass back fixes; a different block or state there is a misplace.
		for (int i : off) {
			if (a.is(X + i, Y, Z, "air")) missing.add(i);
			else wrong.add((X + i) + ":" + ROW.get(i));
		}
		Map<Integer, String> moved = new TreeMap<>();
		for (int i = 0; i < 9; i++) {
			String now = t.player().item(i).replace("minecraft:", "");
			if (!now.equals(p.hotbar.getOrDefault(i, ""))) moved.put(i, now);
		}
		String offhand = t.player().item(40).replace("minecraft:", "");
		// Standing at fixed stops, some blocks have no face to click from any of them: there, misplaces fail, and so does
		// getting less than half the row (nothing placed at all once passed as "nothing misplaced").
		boolean ok = (p.stand != null ? wrong.isEmpty() && n - off.size() >= n / 2 : off.isEmpty()) && moved.isEmpty() && offhand.equals("totem_of_undying")
			&& placedSkipped.isEmpty();
		String detail = (n - off.size()) + "/" + n + " right, " + missing.size() + " not placed, " + wrong.size() + " wrong"
			+ (wrong.isEmpty() ? "" : " (" + String.join(", ", wrong.subList(0, Math.min(6, wrong.size()))) + ")")
			+ (skipped.isEmpty() ? "" : ", " + (skipped.size() - placedSkipped.size()) + "/" + skipped.size() + " skipped left out")
			+ ", hotbar " + (moved.isEmpty() ? "as it was" : slots(moved)) + ", off hand " + (offhand.isEmpty() ? "empty" : offhand);
		return new Result(ok, detail);
	}

	/** Slots and their items as the old suite printed them ({@code {3: 'stone'}}), so the two read alike. */
	private static String slots(Map<Integer, String> moved) {
		List<String> parts = new ArrayList<>();
		moved.forEach((slot, item) -> parts.add(slot + ": '" + item + "'"));
		return "{" + String.join(", ", parts) + "}";
	}

	private static void judge(TestContext t, Pass p) {
		Result r = printerPass(t, p);
		t.check(r.ok, r.detail);
		t.note(r.detail);
	}

	// Rotate on: this server's Grim checks the look on every click (RotationPlace), as 2b2t's doesn't.

	@Test(tags = "printer")
	public void standing(TestContext t) {
		Pass p = new Pass();
		p.stand = new int[] {1, 4, 7, 10, 13, 16, 19, 22};
		judge(t, p);
	}

	@Test(tags = "printer")
	public void walking(TestContext t) {
		judge(t, new Pass().keys(Key.FORWARD));
	}

	@Test(tags = "printer")
	public void sprinting(TestContext t) {
		judge(t, new Pass().keys(Key.FORWARD, Key.SPRINT));
	}

	@Test(tags = "printer")
	public void jumping(TestContext t) {
		judge(t, new Pass().keys(Key.FORWARD, Key.SPRINT, Key.JUMP));
	}

	@Test(tags = "printer")
	public void skip(TestContext t) {
		// Glass in Skip: its two places stay air, everything else is built.
		Pass p = new Pass().keys(Key.FORWARD).set("skip", "add minecraft:glass");
		p.leftOut = Set.of("glass");
		judge(t, p);
	}

	@Test(tags = "printer")
	public void restock(TestContext t) {
		// No glass on you: a chest beside the row has it, and the Printer fetches a stack once glass is all that's left in reach.
		Pass p = new Pass().set("restock", "true").set("from_shulker_boxes", "false").set("from_containers", "true");
		p.stand = new int[] {6, 9, 20};
		p.stopTicks = 80;
		p.omit = Set.of("glass");
		p.setup = c -> {
			c.arena().set(X + 9, Y, Z - 3, "chest");
			c.server().run("item replace block " + c.arena().block(X + 9, Y, Z - 3) + " container.0 with glass 32");
		};
		Result r = printerPass(t, p);
		String first = t.arena().items(X + 9, Y, Z - 3, 27).get(0);
		int left = first.isEmpty() ? 0 : Integer.parseInt(first.substring(first.lastIndexOf(" x") + 2));
		int glass = 0, built = 0;
		for (int i = 0; i < ROW.size(); i++) {
			if (!ROW.get(i).equals("glass")) continue;
			glass++;
			if (t.arena().is(X + i, Y, Z, "glass")) built++;
		}
		String detail = r.detail + "; glass placed " + built + "/" + glass + ", " + (32 - left) + " taken from the chest";
		t.check(r.ok && built == glass && left < 32, detail);
		t.note(detail);
	}

	@Test(tags = "printer", timeoutTicks = 2400)
	public void walk(TestContext t) {
		// Standing 20 blocks short of the row: Walk To Blocks walks over (Baritone) and builds it.
		if (!t.hasMod("baritone")) t.skip("Baritone isn't installed in the dev client (run/mods)");
		Pass p = new Pass().set("walk_to_blocks", "true");
		p.startX = -24.5;
		p.ticks = 1000;
		judge(t, p);
	}

	// 2b2t's settings: Rotate off, Air Place on, with the placement checks 2b2t's Grim doesn't enforce only flagging.

	@Test(name = "walking_2b2t", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void walking2b2t(TestContext t) {
		judge(t, new Pass().twoBTwoT().keys(Key.FORWARD));
	}

	@Test(name = "sprinting_2b2t", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void sprinting2b2t(TestContext t) {
		judge(t, new Pass().twoBTwoT().keys(Key.FORWARD, Key.SPRINT));
	}

	@Test(name = "jumping_2b2t", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void jumping2b2t(TestContext t) {
		judge(t, new Pass().twoBTwoT().keys(Key.FORWARD, Key.SPRINT, Key.JUMP));
	}

	@Test(name = "full_hotbar_2b2t", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void fullHotbar2b2t(TestContext t) {
		// Every slot holding something of yours: the blocks have to borrow one and put it back.
		Pass p = new Pass().twoBTwoT().keys(Key.FORWARD);
		Map<Integer, String> hotbar = new TreeMap<>();
		for (int i = 0; i < FULL_HOTBAR.size(); i++) hotbar.put(i, FULL_HOTBAR.get(i));
		p.hotbar = hotbar;
		judge(t, p);
	}
}
