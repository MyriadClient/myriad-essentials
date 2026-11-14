package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.GrimFlag;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import dev.myriadclient.testing.TestFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elytra Fly's Recast: bouncing along 2b2t's highways, straight (from x 3000 east, its floor at y 100) and diagonal (from
 * x 6000 along +x +z), round and through what's in the lane, with Elytra Swap keeping the elytra from wearing. The
 * highways are far outside the arena: each test builds its own, force-loaded first.
 */
public final class RecastTests extends EssentialsSuite {
	/** 2b2t's highways: obsidian 9 across with a one-block rail along each edge, so 7 to walk between the rails. */
	private static final int RAIL = 4;
	/** Its diagonals are a block wider each side, counted the same way (along z for each x). */
	private static final int DIAGONAL_RAIL = RAIL + 1;
	/** Where the diagonal starts (x; z 0). */
	private static final int DIAGONAL_X0 = 6000;
	/** Where the straight highway ends unless a test needs it longer. */
	private static final int END = 3400;
	private static final String PICKAXE_EFFICIENCY = "netherite_pickaxe[enchantments={efficiency:5}]";
	private static final Pattern DAMAGE = Pattern.compile("damage\"?: (\\d+)");

	/** A force-loaded chunk (absolute chunk coordinates). */
	private record Chunk(int x, int z) {
	}

	private static TestContext.ModuleControl elytraFly(TestContext t) {
		return t.module("myriad-essentials:elytra_fly");
	}

	/**
	 * A highway as 2b2t's (RAIL) at y 100 from x 2990 to {@code end}, the tester on it at x 3000 facing east wearing an
	 * elytra, then {@code setup}. Grim's flags count from after it.
	 */
	private static void buildHighway(TestContext t, int end, Runnable setup) {
		t.arena().forceload(2990, -8, end, 8);
		// Force-loaded chunks take the fills without anyone there (the arena splits them for the server's limit on one fill).
		t.arena().fill(2990, 100, -6, end, 106, 6, "air")
			.fill(2990, 100, -RAIL, end, 100, RAIL, "obsidian")
			.fill(2990, 101, -RAIL, end, 101, -RAIL, "obsidian")
			.fill(2990, 101, RAIL, end, 101, RAIL, "obsidian");
		t.player().give(38, "elytra").at(3000.5, 101, 0.5);
		setup.run();
		settle(t);
	}

	private static void buildHighway(TestContext t, Runnable setup) {
		buildHighway(t, END, setup);
	}

	/**
	 * Waits until the client stands on what was built (it has the chunks there) and a second more; Grim's opinion of the
	 * setup (teleporting you mid-move) isn't the module's, so flags count from here.
	 */
	private static void settle(TestContext t) {
		t.waitUntil("standing on the highway", () -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.player != null && mc.player.onGround();
		}, 100);
		t.waitTicks(20);
		t.flagsFromHere();
	}

	/**
	 * 2b2t's diagonal (+x +z): for each x from DIAGONAL_X0, its cross-section (DIAGONAL_RAIL) along z, centred on z = x -
	 * DIAGONAL_X0, so the rails are staircases of blocks touching at their corners. The tester at its start facing along
	 * it, then {@code setup}. Returns the chunks it force-loaded, to {@link #unforce} once the test is done with them.
	 */
	private static Set<Chunk> buildDiagonal(TestContext t, int length, Runnable setup) {
		int x0 = DIAGONAL_X0;
		// Only the chunks along it: the square it crosses would be a thousand.
		Set<Chunk> chunks = new LinkedHashSet<>();
		for (int i = -9; i < length + 9; i++)
			for (int dx : new int[] {-9, 9})
				for (int dz : new int[] {-9, 9}) chunks.add(new Chunk(t.arena().x(x0 + i + dx) >> 4, t.arena().z(i + dz) >> 4));
		forceload(t, chunks);
		for (int i = -5; i < length; i++) {
			int x = x0 + i;
			t.arena().fill(x, 100, i - 7, x, 106, i + 7, "air")
				.fill(x, 100, i - DIAGONAL_RAIL, x, 100, i + DIAGONAL_RAIL, "obsidian")
				.set(x, 101, i - DIAGONAL_RAIL, "obsidian")
				.set(x, 101, i + DIAGONAL_RAIL, "obsidian");
		}
		t.player().give(38, "elytra").at(x0 + 0.5, 101, 0.5, -45, 0);
		setup.run();
		settle(t);
		return chunks;
	}

	/**
	 * Fills across the diagonal at {@code at} blocks along it: the cells with x + z (from its start) at 2 * at to 2 * at +
	 * width - 1, between the rails, from y1 to y2.
	 */
	private static void diagonalStrip(TestContext t, int at, int width, int y1, int y2, String block) {
		for (int i = at - DIAGONAL_RAIL - width; i < at + DIAGONAL_RAIL + width; i++) {
			for (int z = i - DIAGONAL_RAIL + 1; z < i + DIAGONAL_RAIL; z++) {
				if (2 * at <= i + z && i + z < 2 * at + width) t.arena().fill(DIAGONAL_X0 + i, y1, z, DIAGONAL_X0 + i, y2, z, block);
			}
		}
	}

	/** Force-loads {@code chunks} and waits until the server has them all (a fill reaching one that isn't in yet fails). */
	private static void forceload(TestContext t, Set<Chunk> chunks) {
		for (Chunk c : chunks) t.server().run("forceload add " + (c.x << 4) + " " + (c.z << 4));
		for (Chunk c : chunks) {
			String loaded = "if loaded " + (c.x << 4) + " " + t.arena().y(0) + " " + (c.z << 4);
			long until = System.currentTimeMillis() + 30_000;
			while (!t.server().passes(loaded)) {
				if (System.currentTimeMillis() > until) throw new TestFailure("the server never loaded the chunk at " + (c.x << 4) + " " + (c.z << 4));
				t.waitTicks(2);
			}
		}
	}

	/** Lets the diagonal's chunks go again (only those: the arena's own stay force-loaded). */
	private static void unforce(TestContext t, Set<Chunk> chunks) {
		for (Chunk c : chunks) t.server().run("forceload remove " + (c.x << 4) + " " + (c.z << 4));
	}

	/** Recast on (10 ticks after its settings were given), {@code ticks} of bouncing, then off and 20 ticks to settle. */
	private static void recast(TestContext t, int ticks) {
		t.waitTicks(10);
		elytraFly(t).set("type", "recast").enable();
		t.waitTicks(ticks);
		elytraFly(t).disable();
		t.waitTicks(20);
	}

	/** The item in an inventory slot as the server stores it (102 the chest, 0-35 carried), as printed ("" if empty). */
	private static String slotData(TestContext t, int nbtSlot) {
		return t.player().data("Inventory[{Slot:" + nbtSlot + "b}]");
	}

	private static int damageIn(String slotData) {
		Matcher m = DAMAGE.matcher(slotData);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	/** The elytra's damage, worn (chest) or swapped off into the hotbar; -1 if it's gone. */
	private static int elytraDamage(TestContext t) {
		for (int slot : new int[] {102, 0, 1, 2}) {
			String d = slotData(t, slot);
			if (d.contains("elytra")) return damageIn(d);
		}
		return -1;
	}

	/** One elytra: the server's slot number for it (102 worn), printed as the old suite did, and its damage. */
	private record Elytra(int slot, int damage) {
		@Override
		public String toString() {
			return "(" + slot + ", " + damage + ")";
		}
	}

	/** Every elytra the tester has, worn (102) or carried. */
	private static List<Elytra> elytras(TestContext t) {
		List<Elytra> out = new ArrayList<>();
		List<Integer> slots = new ArrayList<>(List.of(102));
		for (int i = 0; i < 36; i++) slots.add(i);
		for (int slot : slots) {
			String d = slotData(t, slot);
			if (d.contains("elytra")) out.add(new Elytra(slot, damageIn(d)));
		}
		return out;
	}

	private static void needsBaritone(TestContext t) {
		if (!t.hasMod("baritone")) t.skip("Baritone isn't installed in the dev client (run/mods)");
	}

	private static String f(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void highway(TestContext t) {
		buildHighway(t, () -> {
		});
		elytraFly(t).set("obstacles", "stop");
		recast(t, 200);
		Vec3 p = t.player().serverPosition();
		String detail = f("%.0f blocks in 10 s, %.2f off the line", p.x - 3000, Math.abs(p.z - 0.5));
		t.check(p.x - 3000 > 200 && Math.abs(p.z - 0.5) < 0.2, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void obstacles(TestContext t) {
		// An ender chest and a 3-deep hole across the lane, Clear: past the chest (walked round with Baritone, or mined), and
		// the hole flown over, or (a hop coming down in it) stopped before it without Baritone and blocks, or across it with
		// Baritone. Never in it.
		buildHighway(t, () -> {
			t.arena().set(3150, 101, 0, "ender_chest").fill(3250, 98, -1, 3250, 100, 1, "air");
			t.player().give(0, PICKAXE_EFFICIENCY);
		});
		elytraFly(t).set("obstacles", "clear").set("steer_round", "false");
		recast(t, 400);
		Vec3 p = t.player().serverPosition();
		boolean mined = t.arena().is(3150, 101, 0, "air");
		String detail = f("chest %s, x=%.1f (hole at 3250, %s), y=%.1f", mined ? "mined" : "walked round", p.x, t.hasMod("baritone") ? "Baritone" : "no Baritone", p.y);
		t.check(p.x > 3160 && p.y > 100.5, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 3000)
	public void noDurability(TestContext t) {
		// 30 s of bouncing with Elytra Swap: the server's glides must never last the 20 ticks that wear the elytra.
		buildHighway(t, 3980, () -> t.player().give(1, "diamond_chestplate"));
		t.module("myriad-essentials:elytra_swap").enable();
		elytraFly(t).set("obstacles", "stop");
		recast(t, 600);
		double x = t.player().serverPosition().x;
		int damage = elytraDamage(t);
		String detail = f("elytra damage %d after %.0f blocks of bouncing (30 s)", damage, x - 3000);
		t.check(damage >= 0 && damage <= 1 && x - 3000 > 300, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void lowCeiling(TestContext t) {
		// Turned on under a two-high roof: it walks out from under it (mining nothing) and bounces on from there.
		buildHighway(t, () -> {
			t.arena().fill(2995, 103, -1, 3030, 103, 1, "stone");
			t.player().give(0, PICKAXE_EFFICIENCY);
		});
		elytraFly(t).set("obstacles", "clear");
		recast(t, 300);
		double x = t.player().serverPosition().x;
		int roof = t.arena().count(2995, 103, 0, 3030, 103, 0, "stone");
		String detail = f("%.0f blocks in 15 s, %d roof blocks mined", x - 3000, 36 - roof);
		t.check(roof == 36 && x > 3150, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 3000)
	public void diagonal(TestContext t) {
		// 2b2t's diagonals: the server misses more landings here, so Elytra Swap's cut matters most; 15 s of bouncing with
		// the chestplate in the main inventory mustn't wear the elytra.
		int x0 = DIAGONAL_X0;
		Set<Chunk> chunks = buildDiagonal(t, 500, () -> t.player().give(14, "diamond_chestplate"));
		try {
			t.module("myriad-essentials:elytra_swap").enable();
			elytraFly(t).set("obstacles", "stop");
			recast(t, 300);
			Vec3 p = t.player().serverPosition();
			int worn = elytraDamage(t);
			String detail = f("elytra damage %d after %.0f blocks of diagonal, %.2f off the line", worn, (p.x - x0) * 1.414,
				Math.abs(p.x - x0 - p.z + 0.5) / 1.414);
			t.check(worn == 0 && p.x - x0 > 200 && p.y > 100.5, detail);
			t.note(detail);
		} finally {
			unforce(t, chunks);
		}
	}

	@Test(tags = "elytra", timeoutTicks = 3600)
	public void diagonalObstacles(TestContext t) {
		// Obstacles on the diagonal, Clear: an ender chest on the line (walked round between the rails with Baritone, or
		// mined), a pillar two high beside the line, and a 3-deep hole across everything between the rails (flown over, or
		// stopped before or crossed with Baritone). Never in the hole, never off the highway.
		int x0 = DIAGONAL_X0;
		// Long enough that 700 ticks don't reach its end with Baritone's quicker way round the chest.
		Set<Chunk> chunks = buildDiagonal(t, 800, () -> {
			t.arena().set(x0 + 120, 101, 120, "ender_chest").fill(x0 + 200, 101, 201, x0 + 200, 102, 201, "obsidian");
			diagonalStrip(t, 300, 4, 98, 100, "air");
			t.player().give(0, PICKAXE_EFFICIENCY);
		});
		try {
			elytraFly(t).set("obstacles", "clear").set("steer_round", "false");
			recast(t, 700);
			Vec3 p = t.player().serverPosition();
			double along = (p.x - x0 + p.z) / 2, off = (p.x - x0 - p.z) / 1.414;
			// Read while its chunk is still force-loaded.
			boolean mined = t.arena().is(x0 + 120, 101, 120, "air");
			String detail = f("%.0f blocks along (hole at %.0f), %+.2f off the line, y=%.1f, chest %s, %s", along * 1.414, 300 * 1.414, off, p.y,
				mined ? "mined" : "walked round", t.hasMod("baritone") ? "Baritone" : "no Baritone");
			t.check(along > 310 && p.y > 100.5 && Math.abs(off) < 2.2, detail);
			t.note(detail);
		} finally {
			unforce(t, chunks);
		}
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void betweenBlocks(TestContext t) {
		// Line Between Blocks: holes a block wide and two deep in the middle row, every 9 blocks, with Stop. Along the edge
		// between that row and the next, your feet are over both: none of them is in the way, and none is fallen into.
		buildHighway(t, () -> {
			for (int hx = 3030; hx < 3390; hx += 9) t.arena().fill(hx, 98, 0, hx, 100, 0, "air");
		});
		elytraFly(t).set("line", "between_blocks").set("obstacles", "stop");
		recast(t, 300);
		Vec3 p = t.player().serverPosition();
		String detail = f("x=%.0f past 40 holes (from 3030), y=%.1f, z=%.2f (the edge at 1)", p.x, p.y, p.z);
		t.check(p.x > 3300 && p.y > 100.5 && Math.abs(p.z - 1) < 0.25, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void steerRound(TestContext t) {
		// Steer Round, with Stop (so nothing is walked round or cleared): an ender chest on the line, a wall three wide
		// across the middle and a hole two wide and three deep, each with room beside it inside the rails. Bounced round
		// each without stopping, back on the line after.
		buildHighway(t, () -> t.arena().set(3100, 101, 0, "ender_chest").fill(3200, 101, -1, 3200, 102, 1, "obsidian").fill(3300, 98, 0, 3301, 100, 1, "air"));
		elytraFly(t).set("obstacles", "stop");
		recast(t, 300);
		Vec3 p = t.player().serverPosition();
		String detail = f("x=%.0f (chest at 3100, wall at 3200, hole at 3300), y=%.1f, %+.2f off the line", p.x, p.y, p.z - 0.5);
		t.check(p.x > 3340 && p.y > 100.5 && Math.abs(p.z - 0.5) < 0.5, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 3000)
	public void steerRoundDiagonal(TestContext t) {
		// Steer Round on the diagonal, with Stop: an ender chest on the line, bounced round between the rails (a diagonal
		// move takes the corner of an L, so this is the tight one).
		int x0 = DIAGONAL_X0;
		Set<Chunk> chunks = buildDiagonal(t, 400, () -> t.arena().set(x0 + 150, 101, 150, "ender_chest"));
		try {
			elytraFly(t).set("obstacles", "stop");
			recast(t, 300);
			Vec3 p = t.player().serverPosition();
			double along = (p.x - x0 + p.z) / 2, off = (p.x - x0 - p.z) / 1.414;
			String detail = f("%.0f blocks along (chest at %.0f), %+.2f off the line, y=%.1f", along * 1.414, 150 * 1.414, off, p.y);
			t.check(along > 250 && p.y > 100.5 && Math.abs(off) < 0.6, detail);
			t.note(detail);
		} finally {
			unforce(t, chunks);
		}
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void steerRoundCluster(TestContext t) {
		// Steer Round, with Stop: two ender chests on the line 10 blocks apart. Coming back to the line between them would
		// run into the second: it stays out at the side past both, without stopping.
		buildHighway(t, () -> t.arena().set(3100, 101, 0, "ender_chest").set(3110, 101, 0, "ender_chest"));
		elytraFly(t).set("obstacles", "stop");
		recast(t, 240);
		Vec3 p = t.player().serverPosition();
		String detail = f("x=%.0f (chests at 3100 and 3110), y=%.1f, %+.2f off the line", p.x, p.y, p.z - 0.5);
		t.check(p.x > 3250 && p.y > 100.5 && Math.abs(p.z - 0.5) < 0.5, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void handoffCluster(TestContext t) {
		// Clear with Baritone, Steer Round off: three ender chests close together on the line. Baritone takes you to where
		// bouncing can go on, past all three at once (one walk of about 6 s); to just past the first (then again for each)
		// it would creep a few blocks per hand-over and be well short of this in 20 s.
		needsBaritone(t);
		buildHighway(t, 3800, () -> t.arena().set(3100, 101, 0, "ender_chest").set(3104, 101, 0, "ender_chest").set(3108, 101, 0, "ender_chest"));
		elytraFly(t).set("obstacles", "clear").set("steer_round", "false");
		recast(t, 400);
		Vec3 p = t.player().serverPosition();
		String detail = f("x=%.0f in 20 s (chests at 3100, 3104, 3108), y=%.1f", p.x, p.y);
		t.check(p.x > 3280 && p.y > 100.5, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void spareElytras(TestContext t) {
		// Three spare elytras and the chestplate in the main inventory, Auto Armor wanting an elytra on (as players set it):
		// the same elytra keeps being used, spares stay where they are and don't wear, and nothing warns.
		buildHighway(t, 3980, () -> t.player().give(14, "diamond_chestplate").give(19, "elytra").give(20, "elytra").give(21, "elytra"));
		t.module("myriad-essentials:auto_armor").set("chest", "elytra").set("await_landing", "false").enable();
		t.module("myriad-essentials:elytra_swap").enable();
		elytraFly(t).set("obstacles", "stop");
		recast(t, 400);
		t.waitTicks(20);
		t.module("myriad-essentials:auto_armor").disable();
		List<Elytra> found = elytras(t);
		long hotbar = found.stream().filter(e -> e.slot < 9).count();
		long worn = found.stream().filter(e -> e.damage > 0).count();
		double x = t.player().serverPosition().x;
		String detail = f("elytras %s, %d in the hotbar, %d worn; %.0f blocks", found, hotbar, worn, x - 3000);
		t.check(hotbar <= 1 && worn <= 1 && x - 3000 > 300, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void fullHotbar(TestContext t) {
		// No hotbar slot for the chestplate: Elytra Swap says so once and the bounce carries on (it used to release your
		// keys every tick trying).
		String[] blocks = {"dirt", "cobblestone", "stone", "oak_planks", "glass", "sand", "gravel", "netherrack", "andesite"};
		buildHighway(t, () -> {
			t.player().give(14, "diamond_chestplate");
			for (int i = 0; i < blocks.length; i++) t.player().give(i, blocks[i], 64);
		});
		t.module("myriad-essentials:elytra_swap").enable();
		elytraFly(t).set("obstacles", "stop");
		recast(t, 200);
		double x = t.player().serverPosition().x;
		// (The warning is a toast, not in the log.) Nothing was clicked: the chestplate is where it was, the hotbar unchanged.
		boolean untouched = t.player().item(14).equals("minecraft:diamond_chestplate") && t.player().item(0).equals("minecraft:dirt");
		String detail = f("%s, %.0f blocks in 10 s", untouched ? "inventory untouched" : "inventory changed", x - 3000);
		t.check(untouched && x - 3000 > 200, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void fillHole(TestContext t) {
		// A 3-deep hole across the lane, within the first hops: flown over where no hop comes down in it, otherwise Clear
		// fills it at floor level with blocks from the hotbar (without Baritone), or Baritone bridges it with them.
		buildHighway(t, () -> {
			t.arena().fill(3006, 97, -(RAIL - 1), 3007, 100, RAIL - 1, "air");
			t.player().give(2, "obsidian", 64);
		});
		elytraFly(t).set("obstacles", "clear");
		recast(t, 400);
		Vec3 p = t.player().serverPosition();
		int filled = t.arena().count(3006, 100, -1, 3007, 100, 1, "obsidian");
		int above = t.arena().count(3000, 101, -1, 3011, 101, 1, "obsidian");
		String how = filled > 0 ? filled + "/6 hole blocks filled" : "flown over";
		String detail = f("%s, %d placed above the floor, x=%.0f, y=%.1f", how, above, p.x, p.y);
		t.check(above == 0 && p.x > 3150 && p.y > 100.5, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void holesFlownOver(TestContext t) {
		// Single-block holes on the lane's line, a block deep and two deep, Clear: only where a hop comes down is one in the
		// way, so most are flown over (stopping at each, Baritone taking you past, takes about 4 s apiece: 2 of the 6 in 20 s),
		// and none is landed in.
		int[] holes = {3093, 3131, 3178, 3219, 3262, 3307};
		buildHighway(t, () -> {
			for (int i = 0; i < holes.length; i++) t.arena().fill(holes[i], 100 - i % 2, 0, holes[i], 100, 0, "air");
			t.player().give(2, "obsidian", 64);
		});
		elytraFly(t).set("obstacles", "clear");
		recast(t, 500);
		Vec3 p = t.player().serverPosition();
		int passedOver = 0;
		for (int hx : holes) if (hx < p.x) passedOver++;
		String detail = f("%d/%d holes passed in 25 s, x=%.0f, y=%.1f", passedOver, holes.length, p.x, p.y);
		t.check(p.y > 100.5 && passedOver == holes.length, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void highRoof(TestContext t) {
		// A roof of closed top-half trapdoors 2.81 above the floor over 200 blocks of lane: above anything a hop reaches (it
		// rises standing to 2.55 at most), so it bounces on under it, where it walked under anything below 2.9 before; Grim
		// agrees (no flags).
		buildHighway(t, () -> t.arena().fill(3040, 103, -1, 3240, 103, 1, "oak_trapdoor[half=top,open=false]"));
		elytraFly(t).set("obstacles", "stop");
		recast(t, 200);
		double x = t.player().serverPosition().x;
		String detail = f("%.0f blocks in 10 s", x - 3000);
		t.check(x - 3000 > 200, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void portal(TestContext t) {
		// Baritone installed: a lit nether portal across the lane in an obsidian wall, a way round on either side. Baritone
		// walks round it, never through it: the tester is still in the dimension it started in, and past it.
		needsBaritone(t);
		buildHighway(t, () -> {
			t.arena().fill(3090, 100, -5, 3110, 100, 5, "obsidian")
				.fill(3100, 100, -1, 3100, 104, 2, "obsidian")
				.fill(3100, 101, 0, 3100, 103, 1, "nether_portal[axis=z]");
			t.player().give(0, "netherite_pickaxe");
		});
		String dim0 = t.player().data("Dimension");
		elytraFly(t).set("obstacles", "clear").set("steer_round", "false");
		recast(t, 500);
		Vec3 p = t.player().serverPosition();
		String dim = t.player().data("Dimension");
		boolean portal = t.arena().is(3100, 102, 0, "nether_portal");
		String detail = f("x=%.0f, %s, portal %s", p.x, dim.strip(), portal ? "there" : "broken");
		t.check(!dim.isEmpty() && dim.equals(dim0) && p.x > 3105 && portal, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2800)
	public void hungry(TestContext t) {
		// Too hungry to sprint (food drained to 6 or less, Auto Eat off): it stops and waits, rather than hopping on the
		// spot, taking that for stuck and calling Baritone; fed again, it bounces on.
		buildHighway(t, () -> {
		});
		String me = t.player().name();
		elytraFly(t).set("obstacles", "clear");
		t.waitTicks(10);
		elytraFly(t).set("type", "recast").enable();
		t.waitTicks(60);
		t.server().run("effect clear " + me);
		t.server().run("effect give " + me + " hunger 12 255 true");
		t.waitTicks(280);
		double x1 = t.player().serverPosition().x;
		String food = t.player().data("foodLevel");
		t.waitTicks(60);
		double x2 = t.player().serverPosition().x;
		t.server().run("effect give " + me + " saturation 2 20 true");
		t.waitTicks(200);
		elytraFly(t).disable();
		t.waitTicks(20);
		double x3 = t.player().serverPosition().x;
		String detail = f("food %s: stood still %.1f blocks, then %.0f blocks once fed", food, Math.abs(x2 - x1), x3 - x2);
		t.check(Math.abs(x2 - x1) < 1 && x3 - x2 > 30, detail);
		t.note(detail);
	}

	// Flaky in the old suite: flags Simulation now and then with three clients running (load); passes alone.
	@Test(tags = "elytra", timeoutTicks = 2400)
	public void walkRound(TestContext t) {
		// Baritone installed: an ender chest and a bedrock pillar in the lane are walked round (the chest left alone).
		needsBaritone(t);
		buildHighway(t, () -> {
			t.arena().set(3100, 101, 0, "ender_chest").fill(3160, 101, 0, 3160, 102, 0, "bedrock");
			t.player().give(0, "netherite_pickaxe");
		});
		elytraFly(t).set("obstacles", "clear").set("steer_round", "false");
		recast(t, 500);
		Vec3 p = t.player().serverPosition();
		boolean chest = t.arena().is(3100, 101, 0, "ender_chest");
		String detail = f("chest %s, x=%.0f, y=%.1f", chest ? "left" : "mined", p.x, p.y);
		t.check(chest && p.x > 3170 && p.y > 100.5, detail);
		t.note(detail);
	}

	// Flaky in the old suite: flags Simulation now and then with three clients running (load); passes alone.
	@Test(tags = "elytra", timeoutTicks = 3200)
	public void backOnLane(TestContext t) {
		// Knocked off the highway (onto a ledge 4 below, beside it) while bouncing: Baritone takes you back up onto the lane
		// at its height, and the bounce goes on.
		needsBaritone(t);
		buildHighway(t, () -> {
			t.arena().fill(3055, 95, 2, 3095, 106, 9, "air").fill(3060, 95, 3, 3090, 95, 9, "stone");
			t.player().give(3, "dirt", 64);
		});
		elytraFly(t).set("obstacles", "clear");
		t.waitTicks(10);
		elytraFly(t).set("type", "recast").enable();
		t.waitTicks(40);
		t.server().run("tp " + t.player().name() + " " + t.arena().at(3075.5, 96, 6.5));
		// The knock-off is the test's teleport: Grim's take on it isn't the module's.
		t.waitTicks(60);
		t.flagsFromHere();
		t.waitTicks(560);
		elytraFly(t).disable();
		t.waitTicks(20);
		Vec3 p = t.player().serverPosition();
		String detail = f("x=%.0f, y=%.1f, %.1f off the line", p.x, p.y, Math.abs(p.z - 0.5));
		t.check(p.y > 100.5 && p.x > 3120 && Math.abs(p.z - 0.5) < 1.5, detail);
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 3000)
	public void disconnect(TestContext t) {
		// Stop with If Blocked Disconnect: a chest in the lane logs you out (then the tester joins again).
		buildHighway(t, () -> t.arena().set(3100, 101, 0, "ender_chest"));
		elytraFly(t).set("obstacles", "stop").set("if_blocked", "disconnect").set("steer_round", "false");
		t.waitTicks(10);
		elytraFly(t).set("type", "recast").enable();
		// Grim's flags count up to the disconnect: joining again, its first look at you measures the move from where you
		// left. They're read while still in the world (once out, the player has no name to find them by).
		List<GrimFlag> before = List.of();
		for (int waited = 0; waited < 160 && t.player().online(); waited += 4) {
			List<GrimFlag> now = t.flags();
			if (!t.player().online()) break;
			before = now;
			t.waitTicks(4);
		}
		boolean left = !t.player().online();
		Set<String> flagged = new TreeSet<>();
		for (GrimFlag flag : before) flagged.add(flag.check());
		t.waitTicks(40);
		boolean back = t.player().rejoin(30);
		t.waitTicks(100);
		elytraFly(t).set("if_blocked", "wait").set("obstacles", "clear").disable();
		t.flagsFromHere();
		String detail = (left ? "disconnected" : "still online") + (flagged.isEmpty() ? "" : ", flagged before it: " + String.join(", ", flagged));
		t.check(left && flagged.isEmpty(), detail);
		t.check(back, "joined again after it");
		t.note(detail);
	}

	@Test(tags = "elytra", timeoutTicks = 2400)
	public void terrain(TestContext t) {
		// Ground Terrain on a strip that climbs a block twice, drops two and climbs again, then a wall: the steps are hopped
		// up (the hop rises without its dive), the drop glided down, and it stops in front of the wall, unhurt.
		buildHighway(t, () -> t.arena().fill(3040, 101, -1, 3400, 101, 1, "obsidian")
			.fill(3070, 102, -1, 3400, 102, 1, "obsidian")
			.fill(3110, 101, -1, 3400, 102, 1, "air")
			.fill(3150, 101, -1, 3400, 101, 1, "obsidian")
			.fill(3250, 102, -1, 3250, 104, 1, "obsidian"));
		elytraFly(t).set("ground", "terrain");
		recast(t, 300);
		Vec3 p = t.player().serverPosition();
		float hp = t.player().health();
		String detail = f("stopped at x=%.0f (wall at 3250), y=%.1f, health %.1f", p.x, p.y, hp);
		t.check(p.x > 3200 && p.x < 3250 && Math.abs(p.y - 102) < 0.1 && hp >= 20, detail);
		t.note(detail);
	}
}
