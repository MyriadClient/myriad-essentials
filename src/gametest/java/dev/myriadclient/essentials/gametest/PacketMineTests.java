package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

/** Packet Mine: a block mined with the best tool from another hotbar slot. */
public final class PacketMineTests extends EssentialsSuite {
	@Test(tags = "world")
	public void fromAnotherSlot(TestContext t) {
		t.arena().set(3, 0, 0, "deepslate");
		t.player().give(0, "netherite_pickaxe").give(1, "dirt", 16);
		t.player().select(1);
		t.module("myriad-essentials:packet_mine").enable();
		// Eyes at (0.5, 1.62, 0.5) to the block's centre (3.5, 0.5, 0.5).
		t.player().look(-90, 20.47f);
		t.waitTicks(2);
		t.step("attack");
		t.waitTicks(40);
		t.module("myriad-essentials:packet_mine").disable();
		t.check(t.arena().is(3, 0, 0, "air"), "deepslate mined with the pickaxe from another slot");
		t.note("deepslate mined with the pickaxe from another slot");
	}
}
