package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

/**
 * Nuker on amethyst buds hanging overhead, as in a geode: every bud in reach goes, hit on a face towards you (Grim
 * refuses a top face from below), a cluster not in the filter stays, and so does a bud whose block space is in reach but
 * whose own small shape isn't. Waterlogged buds go too, Avoid Fluids or not: their water stays where it was.
 */
public final class NukerTests extends EssentialsSuite {
	/** Buds within reach, under an amethyst ceiling (eyes at 0.5 1.62 0.5). */
	private static final int[][] BUDS = {{1, 3, 0}, {2, 3, 0}, {1, 3, 1}, {2, 3, -1}, {0, 3, 2}, {-1, 3, 0}};
	/** Its block space 4.23 from the eyes, the bud itself 4.92: out of reach. */
	private static final int[] FAR = {4, 4, 0};
	private static final int[] CLUSTER = {1, 3, -1};

	private static void scene(TestContext t, boolean wet) {
		String bud = "small_amethyst_bud[facing=down,waterlogged=" + wet + "]";
		var a = t.arena();
		for (int[] b : BUDS) a.set(b[0], b[1] + 1, b[2], "amethyst_block");
		a.set(CLUSTER[0], CLUSTER[1] + 1, CLUSTER[2], "amethyst_block");
		a.set(FAR[0], FAR[1] + 1, FAR[2], "amethyst_block");
		for (int[] b : BUDS) a.set(b[0], b[1], b[2], bud);
		a.set(CLUSTER[0], CLUSTER[1], CLUSTER[2], "amethyst_cluster[facing=down]");
		a.set(FAR[0], FAR[1], FAR[2], bud);
		t.player().give(0, "netherite_pickaxe[enchantments={efficiency:5}]");
		t.player().select(0);
	}

	private static void nukeBuds(TestContext t, boolean rotate, boolean wet) {
		scene(t, wet);
		t.flagsFromHere();
		t.module("myriad-essentials:nuker").set("filter.filter", "only").set("filter.blocks", "add minecraft:small_amethyst_bud")
			.set("rotate", String.valueOf(rotate)).enable();
		t.waitUntil("every bud in reach broken", () -> gone(t) == BUDS.length, 200);
		t.waitTicks(20);
		t.module("myriad-essentials:nuker").disable();
		int gone = gone(t);
		boolean far = t.arena().is(FAR[0], FAR[1], FAR[2], "small_amethyst_bud[facing=down,waterlogged=" + wet + "]");
		boolean cluster = t.arena().is(CLUSTER[0], CLUSTER[1], CLUSTER[2], "amethyst_cluster[facing=down]");
		t.check(gone == BUDS.length, gone + " of " + BUDS.length + " buds in reach broken");
		t.check(far, "the bud out of reach by its shape was broken");
		t.check(cluster, "the cluster, not in the filter, was broken");
		t.noFlags();
		t.note(gone + " of " + BUDS.length + " buds overhead, the far bud and the cluster left");
	}

	private static int gone(TestContext t) {
		int n = 0;
		// A waterlogged one leaves its water.
		for (int[] b : BUDS) if (t.arena().is(b[0], b[1], b[2], "air") || t.arena().is(b[0], b[1], b[2], "water")) n++;
		return n;
	}

	@Test(name = "buds_overhead", tags = "world")
	public void budsOverhead(TestContext t) {
		nukeBuds(t, false, false);
	}

	@Test(name = "buds_overhead_rotate", tags = "world")
	public void budsOverheadRotate(TestContext t) {
		nukeBuds(t, true, false);
	}

	/** Waterlogged, as in a flooded geode: Avoid Fluids (on by default) leaves them be no longer, their water stays put. */
	@Test(name = "buds_waterlogged", tags = "world")
	public void budsWaterlogged(TestContext t) {
		nukeBuds(t, false, true);
	}
}
