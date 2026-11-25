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
	private static final Map<Integer, String> HOTBAR = Map.of(0, "diamond_sword", 1, "diamond_pickaxe", 2, "golden_apple");
	private static final List<String> FULL_HOTBAR = List.of("diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "bow", "shield",
		"golden_apple", "totem_of_undying", "shears");

	/** How a pass goes; the defaults are a run past the row with Rotate on. */
	private static final class Pass {
		Key[] keys = {};
		boolean rotate = true, air, lenient;
		/** The x (from the row's start) to stand at in turn instead of moving; null to move. */
		int[] stand;
		/** The z to stand at for each of {@link #stand} (null: beside the row, on the side away from the reference). */
		double[] standZ;
		int stopTicks = 40;
		Map<Integer, String> hotbar = HOTBAR;
		/** The blocks to build (the reference row), west to east. */
		List<String> row = ROW;
		/** More items given after the row's (a bucket). */
		List<String> extra = List.of();
		/** Printer settings beyond the usual ones, in order. */
		Map<String, String> settings = new LinkedHashMap<>();
		/** Items left out of the inventory. */
		Set<String> omit = Set.of();
		/** Blocks in Skip, whose places must stay air. */
		Set<String> leftOut = Set.of();
		/** More of the scene, set up before the teleport to the start. */
		Consumer<TestContext> setup = t -> {
		};
		/** Each position's statuses in the build as they changed (with the tick), for what a missed block waited on. */
		final Map<Long, List<String>> statuses = new java.util.HashMap<>();
		/** Run on the game thread every tick while printing (not while setting up or judging); null for none. */
		Runnable watch;
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

	/** The item a block of the row is placed with: its name without the state or data, a wall sign as its sign. */
	private static String itemOf(String block) {
		int at = block.indexOf('['), data = block.indexOf('{');
		int end = at < 0 ? data < 0 ? block.length() : data : data < 0 ? at : Math.min(at, data);
		return block.substring(0, end).replace("_wall_", "_");
	}

	/** The row's blocks as items, in name order: the inventory holds 16 of each from its first slot on. */
	private static List<String> items(List<String> row) {
		return List.copyOf(new TreeSet<>(row.stream().map(PrinterTests::itemOf).toList()));
	}

	private static Result printerPass(TestContext t, Pass p) {
		if (!t.hasMod("litematica")) t.skip("Litematica isn't in the dev client (start it with -Plitematica)");
		var a = t.arena();
		List<String> row = p.row, items = items(row);
		int n = row.size();
		t.step(".printscene clear");
		a.forceload(X - 32, Z - 8, X + n + 48, Z + DZ + 8);
		a.fill(X - 30, Y - 1, Z - 6, X + n + 44, Y - 1, Z + DZ + 2, "stone");
		a.fill(X - 30, Y, Z - 6, X + n + 44, Y + 6, Z + DZ + 2, "air");
		a.fill(X - 30, Y - 2, Z - 6, X + n + 44, Y - 2, Z + DZ + 2, "stone");
		for (int i = 0; i < n; i++) a.set(X + i, Y, Z + DZ, row.get(i));
		t.server().run("kill @e[type=item,x=" + a.x(X) + ",y=" + a.y(Y) + ",z=" + a.z(Z) + ",distance=..60]");
		t.player().give(40, "totem_of_undying");
		p.hotbar.forEach((slot, item) -> t.player().give(slot, item));
		for (int k = 0; k < items.size(); k++) if (!p.omit.contains(items.get(k))) t.player().give(9 + k, items.get(k), 16);
		for (int k = 0; k < p.extra.size(); k++) t.player().give(9 + items.size() + k, p.extra.get(k));
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
		printer.set("rotate", p.rotate ? "on" : "off").set("air_place", String.valueOf(p.air)).set("click_settings", "true")
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
					t.hold(p.keys).forTicksThenContinue(p.ticks);
					printing(t, p, p.ticks + 10);
				} else {
					printing(t, p, p.ticks);
				}
				printing(t, p, 60);
			} else {
				// Standing still at each of these x in turn, long enough for everything in reach.
				for (int k = 0; k < p.stand.length; k++) {
					int sx = p.stand[k];
					t.player().at(X + sx + 0.5, Y, Z + (p.standZ != null ? p.standZ[k] : -1.5), -90, 20);
					printing(t, p, p.stopTicks);
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
		for (int i = 0; i < n; i++) if (p.leftOut.contains(itemOf(row.get(i)))) skipped.add(i);
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
			else wrong.add(i + ":" + row.get(i) + " is " + seen(t, X + i, Y, Z));
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
		List<String> missed = new ArrayList<>();
		for (int i : missing) missed.add(i + ":" + itemOf(row.get(i)));
		for (int i : off) {
			List<String> seen = p.statuses.getOrDefault(new BlockPos(a.x(X + i), a.y(Y), a.z(Z)).asLong(), List.of());
			t.note(i + " " + row.get(i) + ": " + String.join(" ", seen.subList(Math.max(0, seen.size() - 12), seen.size())));
		}
		String detail = (n - off.size()) + "/" + n + " right, " + missing.size() + " not placed"
			+ (missing.isEmpty() || missing.size() > 6 ? "" : " (" + String.join(", ", missed) + ")") + ", " + wrong.size() + " wrong"
			+ (wrong.isEmpty() ? "" : " (" + String.join(", ", wrong.subList(0, Math.min(6, wrong.size()))) + ")")
			+ (skipped.isEmpty() ? "" : ", " + (skipped.size() - placedSkipped.size()) + "/" + skipped.size() + " skipped left out")
			+ ", hotbar " + (moved.isEmpty() ? "as it was" : slots(moved)) + ", off hand " + (offhand.isEmpty() ? "empty" : offhand);
		return new Result(ok, detail);
	}

	/** Waits {@code ticks} while the Printer works, running the pass's watch and noting the build's statuses every tick. */
	private static void printing(TestContext t, Pass p, int ticks) {
		t.waitUntil("printing", () -> {
			if (p.watch != null) p.watch.run();
			var player = Minecraft.getInstance().player;
			for (var b : dev.myriadclient.api.Myriad.building().running()) for (var st : b.steps()) {
				List<String> seen = p.statuses.computeIfAbsent(st.pos().asLong(), k -> new ArrayList<>());
				String name = st.status().name();
				if (seen.isEmpty() || !seen.getLast().startsWith(name + "@")) seen.add(name + "@" + player.tickCount);
			}
			return false;
		}, ticks);
	}

	/** The block at a relative position as the client sees it, state and all ("chest[facing=north,type=left,...]"). */
	private static String seen(TestContext t, int x, int y, int z) {
		var a = t.arena();
		BlockPos pos = new BlockPos(a.x(x), a.y(y), a.z(z));
		return t.onGame(() -> Minecraft.getInstance().level.getBlockState(pos).toString().replace("Block{minecraft:", "").replace("}", ""));
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

	@Test(name = "restock_shulker_box", tags = "printer")
	public void restockShulkerBox(TestContext t) {
		// No glass on you but a shulker box of it (four stacks), a few slots free: the inventory filled with glass (a slot
		// kept free at first, used by a restock after), more than a stack taken, and the box picked back up, nothing on
		// the ground.
		Pass p = new Pass().set("restock", "true").set("from_shulker_boxes", "true").set("from_containers", "false");
		p.stand = new int[] {6, 9, 20};
		// Long enough at each spot for a restock and the box going back: moved on mid-way, the box is out of reach.
		p.stopTicks = 200;
		p.omit = Set.of("glass");
		Map<Integer, String> hotbar = new TreeMap<>(HOTBAR);
		for (int slot = 3; slot < 8; slot++) hotbar.put(slot, "dirt");
		p.hotbar = hotbar;
		p.setup = c -> {
			// The row's blocks are in 9-20: 21-32 full, 33 and 34 empty, the box in 35 (the hotbar's 8 empty too).
			for (int slot = 21; slot <= 32; slot++) c.player().give(slot, "dirt", 64);
			c.player().give(35, "shulker_box[container=[{slot:0,item:{id:glass,count:64}},{slot:1,item:{id:glass,count:64}},"
				+ "{slot:2,item:{id:glass,count:64}},{slot:3,item:{id:glass,count:64}}]]");
		};
		Result r = printerPass(t, p);
		int glass = 0, boxes = 0, empty = 0, built = 0, row = 0;
		List<String> inventory = t.player().inventory();
		for (int i = 0; i < 36; i++) {
			String s = inventory.get(i);
			if (s.isEmpty()) empty++;
			if (s.startsWith("glass x")) glass += Integer.parseInt(s.substring(7));
			if (s.startsWith("shulker_box x")) boxes++;
		}
		for (int i = 0; i < ROW.size(); i++) {
			if (!ROW.get(i).equals("glass")) continue;
			row++;
			if (t.arena().is(X + i, Y, Z, "glass")) built++;
		}
		int taken = glass + built;
		boolean onGround = t.server().passes("at " + t.player().name() + " if entity @e[type=item,distance=..16]");
		String detail = String.format(java.util.Locale.ROOT, "%s; glass placed %d/%d, %d taken from the box, %d slots left empty, box %s%s",
			r.detail, built, row, taken, empty, boxes == 1 ? "back" : "missing", onGround ? ", items on the ground" : "");
		t.check(built == row && taken > 64 && empty <= 1 && boxes == 1 && !onGround, detail);
		t.note(detail);
	}

	@Test(tags = "printer", timeoutTicks = 2400)
	public void walk(TestContext t) {
		// Standing 20 blocks short of the row: Walk To Blocks walks over (Baritone) and builds it.
		if (!t.hasMod("baritone")) t.skip("Baritone isn't installed in the dev client (run/mods)");
		Pass p = new Pass().set("walk_to_blocks", "true");
		p.startX = -24.5;
		p.ticks = 1000;
		// How long the row takes, from the Printer's start to its last block (on the client).
		var arena = t.arena();
		int[] startedAt = {-1}, doneAt = {-1};
		p.watch = () -> {
			var mc = Minecraft.getInstance();
			if (startedAt[0] < 0) startedAt[0] = mc.player.tickCount;
			if (doneAt[0] >= 0) return;
			for (int i = 0; i < ROW.size(); i++) if (mc.level.getBlockState(new BlockPos(arena.x(X + i), arena.y(Y), arena.z(Z))).isAir()) return;
			doneAt[0] = mc.player.tickCount;
		};
		judge(t, p);
		t.note(doneAt[0] < 0 ? "row never finished" : "row built in " + (doneAt[0] - startedAt[0]) + " ticks");
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

	// ---- chests, signs, water, the hotbar --------------------------------------------------------------------------

	/**
	 * Single chests beside each other, doubles both ways round, a chest beside a double and a trapped chest, and last a log
	 * along x, whose only side face to click is the chest's.
	 */
	private static final List<String> CHESTS = List.of("stone", "chest[facing=north,type=single]", "chest[facing=north,type=single]", "stone",
		"chest[facing=north,type=left]", "chest[facing=north,type=right]", "chest[facing=north,type=single]", "stone", "chest[facing=south,type=right]",
		"chest[facing=south,type=left]", "trapped_chest[facing=north,type=single]", "chest[facing=north,type=single]", "oak_log[axis=x]");

	@Test(tags = "printer")
	public void chests(TestContext t) {
		Pass p = new Pass();
		p.row = CHESTS;
		p.stand = new int[] {3, 8, 12};
		p.stopTicks = 80;
		judgeAll(t, p);
	}

	@Test(name = "chests_2b2t", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void chests2b2t(TestContext t) {
		Pass p = new Pass().twoBTwoT();
		p.row = CHESTS;
		p.stand = new int[] {3, 8, 12};
		p.stopTicks = 80;
		judgeAll(t, p);
	}

	private static String text(String... lines) {
		List<String> quoted = new ArrayList<>();
		for (String l : lines) quoted.add("'\"" + l + "\"'");
		return "{messages:[" + String.join(",", quoted) + "]}";
	}

	/**
	 * Signs with text: standing, on a wall, hanging on a wall, one with text on both sides (facing south: its back is
	 * written from the north, its front once you stand south of it), and one left blank.
	 */
	private static final List<String> SIGNS = List.of("stone", "oak_sign[rotation=0]{front_text:" + text("Hello", "from", "Myriad", "") + "}", "stone",
		"oak_wall_sign[facing=east]{front_text:" + text("Wall", "", "", "sign") + "}", "stone",
		"oak_wall_hanging_sign[facing=north]{front_text:" + text("Hanging", "", "", "") + "}",
		"birch_sign[rotation=0]{front_text:" + text("Front", "", "", "") + ",back_text:" + text("Back", "", "", "") + "}", "spruce_sign[rotation=8]",
		"stone");

	@Test(tags = "printer")
	public void signs(TestContext t) {
		Pass p = new Pass();
		p.row = SIGNS;
		p.stand = new int[] {2, 6, 6};
		p.standZ = new double[] {-1.5, -1.5, 2.5};
		p.stopTicks = 100;
		judgeAll(t, p);
	}

	@Test(name = "signs_2b2t", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void signs2b2t(TestContext t) {
		Pass p = new Pass().twoBTwoT();
		p.row = SIGNS;
		p.stand = new int[] {2, 6, 6};
		p.standZ = new double[] {-1.5, -1.5, 2.5};
		p.stopTicks = 100;
		judgeAll(t, p);
	}

	/**
	 * Waterlogged blocks, and a dry slab beside one (with only that one beside it: between two, the water would make a
	 * source of it).
	 */
	private static final List<String> WATERLOGGED = List.of("stone", "oak_slab[type=bottom,waterlogged=true]", "oak_slab[type=bottom]", "stone",
		"oak_stairs[facing=north,waterlogged=true]", "stone", "oak_trapdoor[half=bottom,facing=north,waterlogged=true]", "lantern[waterlogged=true]",
		"stone");

	/**
	 * Waterlogged blocks, the water taken with one bucket from a 2x2 pool (which refills) and never from a lone source or
	 * a pair of them (which wouldn't).
	 */
	@Test(tags = "printer", timeoutTicks = 2400)
	public void waterlogged(TestContext t) {
		Pass p = new Pass();
		p.row = WATERLOGGED;
		p.extra = List.of("bucket");
		p.stand = new int[] {2, 5, 2, 5};
		p.stopTicks = 120;
		p.setup = c -> {
			var a = c.arena();
			a.fill(X + 2, Y - 1, Z - 5, X + 3, Y - 1, Z - 4, "water");
			a.set(X - 2, Y - 1, Z - 4, "water");
			a.fill(X + 6, Y - 1, Z - 4, X + 7, Y - 1, Z - 4, "water");
		};
		Result r = printerPass(t, p);
		var a = t.arena();
		int pool = a.count(X + 2, Y - 1, Z - 5, X + 3, Y - 1, Z - 4, "water[level=0]");
		boolean lone = a.is(X - 2, Y - 1, Z - 4, "water[level=0]");
		int pair = a.count(X + 6, Y - 1, Z - 4, X + 7, Y - 1, Z - 4, "water[level=0]");
		String detail = r.detail + "; pool " + pool + "/4, lone source " + (lone ? "kept" : "taken") + ", pair " + pair + "/2";
		t.check(r.ok && pool == 4 && lone && pair == 2, detail);
		t.note(detail);
	}

	/**
	 * A channel of three water sources between stone, walled in along both sides: the stone first (the water waits for
	 * the far end's, its own end of the channel open till then), then the water poured from one bucket filled at a 2x2
	 * pool, twice: both ends, the middle filling in by itself. Stood at each end of the channel in turn, looking along it.
	 */
	@Test(name = "water_sources", tags = "printer", timeoutTicks = 2400)
	public void waterSources(TestContext t) {
		Pass p = new Pass();
		p.row = List.of("stone", "water", "water", "water", "stone");
		p.omit = Set.of("water");
		p.extra = List.of("bucket");
		p.stand = new int[] {5, -1};
		p.standZ = new double[] {0.5, 0.5};
		p.stopTicks = 160;
		int[] pours = {0}, held = {0};
		p.watch = () -> {
			var inv = Minecraft.getInstance().player.getInventory();
			int n = 0;
			for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(net.minecraft.world.item.Items.WATER_BUCKET)) n++;
			if (n < held[0]) pours[0]++;
			held[0] = n;
		};
		p.setup = c -> {
			var a = c.arena();
			// Walls along both sides of the row and of the reference row, so neither's water runs out.
			for (int dz : new int[] {-1, 1}) {
				a.fill(X + 1, Y, Z + dz, X + 3, Y, Z + dz, "stone");
				a.fill(X + 1, Y, Z + DZ + dz, X + 3, Y, Z + DZ + dz, "stone");
			}
			// The pool the bucket is filled at, in the floor beside the west end.
			a.fill(X - 3, Y - 1, Z + 1, X - 2, Y - 1, Z + 2, "water");
		};
		Result r = printerPass(t, p);
		var a = t.arena();
		int sources = a.count(X + 1, Y, Z, X + 3, Y, Z, "water[level=0]");
		int pool = a.count(X - 3, Y - 1, Z + 1, X - 2, Y - 1, Z + 2, "water[level=0]");
		boolean stone = a.is(X, Y, Z, "stone") && a.is(X + 4, Y, Z, "stone");
		String detail = r.detail + "; " + sources + "/3 sources from " + pours[0] + " buckets, pool " + pool + "/4";
		t.check(stone && sources == 3 && pours[0] <= 2 && pool == 4, detail);
		t.note(detail);
	}

	/**
	 * A full hotbar and the row's blocks all in the inventory: while it prints, nothing seems to move (every slot shows
	 * what it held, the slot you hold keeps its item), and once it's off everything is back where it was, the blocks it
	 * used up gone. Inventory clicks are counted (a swap changes two slots).
	 */
	@Test(name = "inventory_kept", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void inventoryKept(TestContext t) {
		inventoryKept(t, new Pass().twoBTwoT().keys(Key.FORWARD));
	}

	@Test(name = "inventory_kept_rotate", tags = "printer")
	public void inventoryKeptRotate(TestContext t) {
		inventoryKept(t, new Pass().keys(Key.FORWARD));
	}

	private static void inventoryKept(TestContext t, Pass p) {
		Map<Integer, String> hotbar = new TreeMap<>();
		for (int i = 0; i < FULL_HOTBAR.size(); i++) hotbar.put(i, FULL_HOTBAR.get(i));
		p.hotbar = hotbar;
		String[][] start = {null}, last = {null};
		int[] seen = {0}, changes = {0}, selectedChanged = {0};
		String[] seenAt = {""};
		p.watch = () -> {
			var player = Minecraft.getInstance().player;
			if (player == null) return;
			var inv = player.getInventory();
			String[] real = new String[36], shown = new String[36];
			for (int i = 0; i < 36; i++) {
				real[i] = name(inv.getItem(i));
				shown[i] = name(dev.myriadclient.api.Myriad.inventory().shown(i));
			}
			if (start[0] == null) start[0] = real;
			for (int i = 0; i < 36; i++) {
				// Shown differently from the start, other than a block of the row used up.
				if (shown[i].equals(start[0][i]) || shown[i].isEmpty() && !hotbar.containsValue(start[0][i].replace("minecraft:", ""))) continue;
				if (seen[0]++ == 0) seenAt[0] = "slot " + i + " showed " + shown[i] + " for " + start[0][i];
			}
			if (!real[inv.getSelectedSlot()].equals(start[0][inv.getSelectedSlot()])) selectedChanged[0]++;
			if (last[0] != null) for (int i = 0; i < 36; i++) if (!real[i].equals(last[0][i])) changes[0]++;
			last[0] = real;
		};
		Result r = printerPass(t, p);
		// Where everything ended: each slot as it started, or empty where a block of the row was used up.
		List<String> misplaced = new ArrayList<>();
		String[] end = t.onGame(() -> {
			var inv = Minecraft.getInstance().player.getInventory();
			String[] now = new String[36];
			for (int i = 0; i < 36; i++) now[i] = name(inv.getItem(i));
			return now;
		});
		for (int i = 0; i < 36 && start[0] != null; i++) {
			if (end[i].equals(start[0][i]) || end[i].isEmpty() && !hotbar.containsValue(start[0][i].replace("minecraft:", ""))) continue;
			misplaced.add(i + ": " + end[i] + " for " + start[0][i]);
		}
		int swaps = changes[0] / 2;
		String detail = r.detail + "; " + seen[0] + " slot views changed" + (seen[0] > 0 ? " (" + seenAt[0] + ")" : "") + ", selected slot changed "
			+ selectedChanged[0] + " ticks, " + misplaced.size() + " misplaced at the end" + (misplaced.isEmpty() ? "" : " " + misplaced.subList(0, Math.min(4, misplaced.size())))
			+ ", ~" + swaps + " swaps";
		t.check(r.ok && seen[0] == 0 && selectedChanged[0] == 0 && misplaced.isEmpty(), detail);
		t.note(detail);
	}

	private static String name(net.minecraft.world.item.ItemStack stack) {
		return stack.isEmpty() ? "" : stack.getItem().toString();
	}

	/** Judges a pass that stands at fixed stops but should still build every block. */
	private static void judgeAll(TestContext t, Pass p) {
		Result r = printerPass(t, p);
		boolean all = r.detail.startsWith(p.row.size() + "/" + p.row.size() + " right");
		t.check(r.ok && all, r.detail);
		t.note(r.detail);
	}

	/**
	 * How long a standing print of what's in reach takes with every block in the inventory and a full hotbar (2b2t's
	 * settings): ticks from turning the Printer on until nothing in reach is left to place, and the moves it made.
	 */
	@Test(name = "speed_2b2t", tags = {"printer", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void speed2b2t(TestContext t) {
		speed(t, new Pass().twoBTwoT());
	}

	private static void speed(TestContext t, Pass p) {
		Map<Integer, String> hotbar = new TreeMap<>();
		for (int i = 0; i < FULL_HOTBAR.size(); i++) hotbar.put(i, FULL_HOTBAR.get(i));
		p.hotbar = hotbar;
		p.stand = new int[] {12};
		p.stopTicks = 120;
		long[] first = {-1}, done = {-1};
		int[] clicks = {0};
		String[][] last = {null};
		p.watch = () -> {
			var player = Minecraft.getInstance().player;
			if (first[0] < 0) first[0] = player.tickCount;
			var inv = player.getInventory();
			String[] now = new String[36];
			for (int i = 0; i < 36; i++) now[i] = inv.getItem(i).getItem().toString();
			if (last[0] != null) for (int i = 0; i < 36; i++) if (!now[i].equals(last[0][i])) clicks[0]++;
			last[0] = now;
			var builds = dev.myriadclient.api.Myriad.building().running();
			if (done[0] < 0 && player.tickCount - first[0] > 5 && !builds.isEmpty() && builds.getFirst().steps().stream()
				.noneMatch(st -> st.status() == dev.myriadclient.api.build.Build.Status.PLACE || st.status() == dev.myriadclient.api.build.Build.Status.WAITING))
				done[0] = player.tickCount - first[0];
		};
		Result r = printerPass(t, p);
		String detail = r.detail + "; everything in reach placed after " + done[0] + " ticks, ~" + clicks[0] / 2 + " swaps";
		t.check(!r.detail.contains(" 0 wrong") ? false : r.detail.contains("hotbar as it was") && done[0] > 0, detail);
		t.note(detail);
	}

	/**
	 * Blocks made from another with Use Tools: none of their own items carried, only the plain blocks and the tools. A
	 * stripped log from a log and an axe, farmland and a path from dirt and a hoe or shovel, a potted poppy from a pot and
	 * a poppy, a candle lit with flint and steel, a campfire put out with a shovel. Random ticks off meanwhile, so the
	 * farmland doesn't dry back to dirt.
	 */
	@Test(tags = "printer", timeoutTicks = 2400)
	public void tools(TestContext t) {
		Pass p = new Pass();
		p.row = List.of("stripped_oak_log[axis=y]", "farmland", "dirt_path", "potted_poppy", "candle[candles=1,lit=true]", "campfire[lit=false]");
		p.omit = Set.of("stripped_oak_log", "farmland", "dirt_path", "potted_poppy");
		p.extra = List.of("oak_log", "dirt", "dirt", "flower_pot", "poppy", "diamond_axe", "diamond_hoe", "diamond_shovel", "flint_and_steel");
		p.stand = new int[] {1, 4};
		p.stopTicks = 120;
		p.set("use_tools", "true");
		t.server().run("gamerule randomTickSpeed 0");
		Result r;
		try {
			r = printerPass(t, p);
		} finally {
			t.server().run("gamerule randomTickSpeed 3");
		}
		t.check(r.ok && r.detail.startsWith("6/6"), r.detail);
		t.note(r.detail);
	}

	/**
	 * An observer and the log it watches, the observer nearer: placed first it would see the log go in and pulse, so it
	 * waits for the log.
	 */
	@Test(name = "observer_last", tags = "printer", timeoutTicks = 2400)
	public void observerLast(TestContext t) {
		Pass p = new Pass();
		p.row = List.of("observer[facing=east]", "oak_log[axis=y]");
		// Out of reach till the stand, so both go in while it's watched. Watched every tick from before the Printer is on:
		// the pass's own watch starts once you've stood there a moment, and both can be in by then.
		p.startX = -20.5;
		p.stand = new int[] {0};
		p.stopTicks = 100;
		int[] firstSeen = {-1, -1};
		int[] tick = {0};
		var a = t.arena();
		BlockPos observer = new BlockPos(a.x(X), a.y(Y), a.z(Z)), log = observer.east();
		Object watch = new Object() {
			@dev.myriadclient.api.event.Subscribe
			private void onTick(dev.myriadclient.api.event.events.TickEvent.Post e) {
				var level = Minecraft.getInstance().level;
				if (level == null) return;
				tick[0]++;
				if (firstSeen[0] < 0 && !level.getBlockState(observer).isAir()) firstSeen[0] = tick[0];
				if (firstSeen[1] < 0 && !level.getBlockState(log).isAir()) firstSeen[1] = tick[0];
			}
		};
		t.onGame(() -> dev.myriadclient.api.Myriad.events().subscribe(watch));
		Result r;
		try {
			r = printerPass(t, p);
		} finally {
			t.onGame(() -> dev.myriadclient.api.Myriad.events().unsubscribe(watch));
		}
		boolean after = firstSeen[0] > firstSeen[1] && firstSeen[1] > 0;
		String detail = r.detail + "; observer at tick " + firstSeen[0] + ", log at " + firstSeen[1];
		t.check(r.ok && after, detail);
		t.note(detail);
	}
}
