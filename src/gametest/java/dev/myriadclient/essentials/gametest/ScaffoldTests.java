package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Scaffold: a block under you at a run, on a diagonal, with Air Place and while towering, the 2b2t way (clicks without a
 * turn, any face) and the strict-Grim way (Rotate). You start at z -10.5 facing south (+z); the floor ends at z -8.
 */
public final class ScaffoldTests extends EssentialsSuite {
	/** Where a pass started and ended (the server's positions). */
	private record Pass(Vec3 from, Vec3 to) {
	}

	/**
	 * Scaffold on, {@code keys} held for {@code ticks}, from (0.5, 0, -10.5) facing {@code yaw}; with {@code clear}, the
	 * floor gone from z -8 on. Without Rotate (or with Air Place) it's 2b2t's way, and the server lets those placements
	 * through as 2b2t's does.
	 */
	private static Pass pass(TestContext t, boolean rotate, boolean air, float yaw, boolean clear, int ticks, Key... keys) {
		t.player().at(0.5, 0, -10.5, yaw, 0);
		if (clear) t.arena().fill(-20, -1, -8, 40, -1, 20, "air");
		t.player().give(0, "dirt", 64).give(9, "dirt", 64).select(0);
		t.module("myriad-essentials:scaffold").set("rotate", String.valueOf(rotate)).set("air_place", String.valueOf(air)).set("blocks", "clear");
		if (!rotate || air) t.server().likeTwoBTwoT();
		Vec3 from = t.player().serverPosition();
		t.module("myriad-essentials:scaffold").enable();
		t.waitTicks(2);
		t.hold(keys).forTicks(ticks);
		t.waitTicks(4);
		return new Pass(from, t.player().serverPosition());
	}

	/** How many of the cells z0..z1 (at x 0, under the floor's height) have dirt. */
	private static int bridged(TestContext t, int z0, int z1) {
		return t.arena().count(0, -1, z0, 0, -1, z1, "dirt");
	}

	@Test(name = "sprinting_2b2t", tags = {"world", "2b2t"}, allowFlags = "RotationPlace")
	public void sprinting2b2t(TestContext t) {
		// Unturned clicks at a sprint: the run keeps its speed (sprinting covers about 8.4 blocks in 30 ticks, walking 6.5).
		Pass p = pass(t, false, false, 0, true, 30, Key.FORWARD, Key.SPRINT);
		int last = (int) Math.floor(p.to.z), n = last + 7 + 1, placed = bridged(t, -7, last);
		double ran = p.to.z - p.from.z;
		t.check(ran >= 7.5 && placed == n && p.to.y >= -0.1, String.format(Locale.ROOT, "ran %.2f blocks, %d/%d bridged, y=%.2f", ran, placed, n, p.to.y));
		t.note(String.format(Locale.ROOT, "ran %.2f blocks, %d/%d bridged", ran, placed, n));
	}

	@Test(name = "diagonal_2b2t", tags = {"world", "2b2t"}, allowFlags = "RotationPlace")
	public void diagonal2b2t(TestContext t) {
		// Running diagonally off the edge: a side cell goes in between each diagonal step, so nothing is left to fall through.
		Pass p = pass(t, false, false, -45, true, 30, Key.FORWARD, Key.SPRINT);
		double gone = Math.hypot(p.to.x - p.from.x, p.to.z - p.from.z);
		t.check(gone >= 6 && p.to.y >= -0.1, String.format(Locale.ROOT, "ran %.2f blocks diagonally, y=%.2f", gone, p.to.y));
		t.note(String.format(Locale.ROOT, "ran %.2f blocks diagonally", gone));
	}

	@Test(name = "air_place_2b2t", tags = {"world", "2b2t"}, allowFlags = "RotationPlace")
	public void airPlace2b2t(TestContext t) {
		// Air Place: every cell straight into the air, no bridging needed.
		Pass p = pass(t, false, true, 0, true, 30, Key.FORWARD, Key.SPRINT);
		int last = (int) Math.floor(p.to.z), n = last + 7 + 1, placed = bridged(t, -7, last);
		double ran = p.to.z - p.from.z;
		t.check(ran >= 7.5 && placed == n && p.to.y >= -0.1, String.format(Locale.ROOT, "ran %.2f blocks, %d/%d placed, y=%.2f", ran, placed, n, p.to.y));
		t.note(String.format(Locale.ROOT, "ran %.2f blocks, %d/%d placed", ran, placed, n));
	}

	@Test(name = "tower_2b2t", tags = {"world", "2b2t"}, allowFlags = "RotationPlace")
	public void tower2b2t(TestContext t) {
		// Jump held in place: a block under you at every jump.
		Pass p = pass(t, false, false, 0, false, 80, Key.JUMP);
		double rose = p.to.y - p.from.y;
		t.check(rose >= 5, String.format(Locale.ROOT, "rose %.2f blocks in 80 ticks", rose));
		t.note(String.format(Locale.ROOT, "rose %.2f blocks", rose));
	}

	@Test(tags = "world")
	public void towerRotate(TestContext t) {
		Pass p = pass(t, true, false, 0, false, 80, Key.JUMP);
		double rose = p.to.y - p.from.y;
		t.check(rose >= 4, String.format(Locale.ROOT, "rose %.2f blocks in 80 ticks", rose));
		t.note(String.format(Locale.ROOT, "rose %.2f blocks", rose));
	}

	@Test(tags = "world")
	public void rotate(TestContext t) {
		// The test server's Grim checks where you look when placing (RotationPlace). Ten ticks after the keys are let go
		// the cell you were held at the edge of goes in; every block walked over from the floor's edge (z -8) is bridged,
		// however far it got (Rotate breaks the sprint at each one).
		pass(t, true, false, 0, true, 30, Key.FORWARD, Key.SPRINT);
		t.waitTicks(8);
		Vec3 to = t.player().serverPosition();
		int last = (int) Math.floor(to.z), n = last + 7 + 1, placed = bridged(t, -7, last);
		t.check(n >= 4 && placed == n && to.y >= -0.1, String.format(Locale.ROOT, "%d/%d blocks bridged under the path, y=%.2f", placed, n, to.y));
		t.note(placed + "/" + n + " blocks bridged under the path");
	}
}
