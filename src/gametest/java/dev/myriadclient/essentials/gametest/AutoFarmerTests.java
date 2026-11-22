package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/** Auto Farmer: the held crop planted on every empty farmland in reach, and nowhere else. */
public final class AutoFarmerTests extends EssentialsSuite {
	private static final String FARMER = "myriad-essentials:auto_farmer";

	/** A 5x5 field under you (farmland, a dirt block at its edge), {@code item} held, planted; how many were. */
	private static int field(TestContext t, String soil, String item, String crop) {
		t.player().at(0.5, 0, 0.5, 0, 60);
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
			t.arena().set(x, -1, z, soil);
			t.arena().set(x, 0, z, "air");
		}
		t.arena().set(3, -1, 0, "dirt");
		t.arena().set(3, 0, 0, "air");
		t.player().give(0, item, 64).select(0);
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(FARMER).enable();
		// Everything but where you stand (a block can't go where you are).
		t.waitUntil("the field planted", () -> planted(t, crop) >= 24, 200);
		t.waitTicks(10);
		t.module(FARMER).disable();
		return planted(t, crop);
	}

	private static int planted(TestContext t, String crop) {
		int n = 0;
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (t.arena().is(x, 0, z, crop)) n++;
		return n;
	}

	@Test(name = "plants_farmland", tags = "automation", timeoutTicks = 600)
	public void plantsFarmland(TestContext t) {
		int n = field(t, "farmland", "wheat_seeds", "wheat");
		boolean dirt = t.arena().is(3, 0, 0, "air");
		String detail = String.format(Locale.ROOT, "%d of 24 farmland planted, dirt %s", n, dirt ? "left alone" : "planted");
		t.check(n >= 24 && dirt, detail);
		t.noFlags();
		t.note(detail);
	}

	@Test(name = "nether_wart", tags = "automation", timeoutTicks = 600)
	public void netherWart(TestContext t) {
		int n = field(t, "soul_sand", "nether_wart", "nether_wart");
		String detail = String.format(Locale.ROOT, "%d of 24 soul sand planted", n);
		t.check(n >= 24, detail);
		t.noFlags();
		t.note(detail);
	}
}
