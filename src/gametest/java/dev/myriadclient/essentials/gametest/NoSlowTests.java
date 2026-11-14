package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/** No Slow: walking while eating, and through cobwebs, as fast as Grim lets you. */
public final class NoSlowTests extends EssentialsSuite {
	@Test(tags = "movement")
	public void items(TestContext t) {
		// A golden apple eaten while walking: one tick in three at full speed, the most Grim's NoSlow lets through.
		t.player().give(0, "golden_apple", 4);
		double x0 = t.player().serverPosition().x;
		t.player().select(0);
		t.module("myriad-essentials:no_slow").enable();
		t.step("mouse 1 press");
		t.hold(Key.FORWARD).forTicks(34);
		t.waitTicks(1);
		t.step("mouse 1 release");
		t.waitTicks(5);
		t.module("myriad-essentials:no_slow").disable();
		double walked = t.player().serverPosition().x - x0;
		int eaten = 4 - t.player().count(0);
		// Walking 34 ticks covers about 7 blocks, and eating slows that to about 1.5; No Slow makes it about 3.
		String detail = String.format(Locale.ROOT, "walked %.2f blocks while eating, %d eaten", walked, eaten);
		t.check(eaten == 1 && walked > 2.5, detail);
		t.note(detail);
	}

	@Test(tags = "movement")
	public void webs(TestContext t) {
		// A block of cobwebs three wide and two high, walked through at full speed: Grim has them as air while the tester
		// is in them (reported broken, which the server refuses), and nothing flags. A TNT block overhead broken first (at
		// once, by hand) is the block Grim's FastBreak times finishes against then, whatever an earlier test left half dug.
		t.arena().set(0, 2, 0, "tnt").fill(2, 0, -1, 12, 1, 1, "cobweb");
		t.player().look(-90, -90);
		t.waitTicks(3);
		t.step("mouse 0 press");
		t.waitTicks(2);
		t.step("mouse 0 release");
		t.player().look(-90, 0);
		t.waitTicks(5);
		double x0 = t.player().serverPosition().x;
		t.module("myriad-essentials:no_slow").enable();
		t.waitTicks(3);
		t.hold(Key.FORWARD).forTicks(30);
		t.waitTicks(1);
		t.module("myriad-essentials:no_slow").disable();
		t.waitTicks(10);
		double walked = t.player().serverPosition().x - x0;
		boolean intact = t.arena().is(4, 0, 0, "cobweb") && t.arena().is(6, 1, 0, "cobweb");
		// Walking 30 ticks covers about 6.5 blocks, and webs slow that to under one.
		String detail = String.format(Locale.ROOT, "walked %.2f blocks through cobwebs, webs still there: %s", walked, intact);
		t.check(walked > 5.5 && intact, detail);
		t.note(detail);
	}

	@Test(name = "webs_after_a_tap", tags = "movement")
	public void websAfterTap(TestContext t) {
		// A stone block tapped and given up stays the block Grim's FastBreak times finishes against, so a web's would be
		// refused: the webs are walked through slowed, as Grim has them, and nothing flags.
		t.arena().set(1, 0, 1, "stone").fill(2, 0, -1, 12, 1, 1, "cobweb");
		t.player().look(-45, 38);
		t.waitTicks(3);
		t.step("mouse 0 press");
		t.waitTicks(4);
		t.step("mouse 0 release");
		t.player().look(-90, 0);
		t.waitTicks(5);
		double x0 = t.player().serverPosition().x;
		t.module("myriad-essentials:no_slow").enable();
		t.waitTicks(3);
		t.hold(Key.FORWARD).forTicks(30);
		t.waitTicks(1);
		t.module("myriad-essentials:no_slow").disable();
		t.waitTicks(10);
		double walked = t.player().serverPosition().x - x0;
		String detail = String.format(Locale.ROOT, "walked %.2f blocks through cobwebs (slowed)", walked);
		t.check(walked > 0.3 && walked < 2.5, detail);
		t.note(detail);
	}
}
