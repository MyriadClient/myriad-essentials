package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

/**
 * Packet Mine: a block mined with the best tool from another hotbar slot, Silk Touch brought in for glass and shears for glow lichen,
 * all as Auto Tool picks (with it off, what you hold).
 */
public final class PacketMineTests extends EssentialsSuite {
	@Test(tags = "world")
	public void fromAnotherSlot(TestContext t) {
		t.arena().set(3, 0, 0, "deepslate");
		t.player().give(0, "netherite_pickaxe").give(1, "dirt", 16);
		t.player().select(1);
		t.module("myriad-essentials:auto_tool").enable();
		t.module("myriad-essentials:packet_mine").enable();
		// Eyes at (0.5, 1.62, 0.5) to the block's centre (3.5, 0.5, 0.5).
		t.player().look(-90, 20.47f);
		t.waitTicks(2);
		t.step("attack");
		t.waitTicks(40);
		t.module("myriad-essentials:packet_mine").disable();
		t.module("myriad-essentials:auto_tool").disable();
		t.check(t.arena().is(3, 0, 0, "air"), "deepslate mined with the pickaxe from another slot");
		t.note("deepslate mined with the pickaxe from another slot");
	}

	/**
	 * Packet Mine on {@code block} with an empty hand and a Silk Touch pickaxe only in the inventory, Auto Tool
	 * {@code autoTool}: how many of {@code drop} came back to you.
	 */
	private static int silkDrop(TestContext t, String block, String drop, boolean autoTool, boolean below) {
		return silkDrop(t, block, drop, autoTool, below, 20.47f);
	}

	/** {@code pitch}: where to look to hit it (the block's middle, or low for a flat one). */
	private static int silkDrop(TestContext t, String block, String drop, boolean autoTool, boolean below, float pitch) {
		var a = t.arena();
		if (below) a.set(3, -1, 0, "stone");
		a.set(3, 0, 0, block);
		t.player().give(15, "diamond_pickaxe[enchantments={silk_touch:1}]");
		t.player().select(0);
		if (autoTool) t.module("myriad-essentials:auto_tool").enable();
		else t.module("myriad-essentials:auto_tool").disable();
		t.module("myriad-essentials:packet_mine").enable();
		t.player().look(-90, pitch);
		t.waitTicks(4);
		t.step("attack");
		t.waitTicks(60);
		t.module("myriad-essentials:packet_mine").disable();
		t.module("myriad-essentials:auto_tool").disable();
		// What dropped, walked over to and picked up.
		t.player().at(3.5, 0, 0.5);
		t.waitTicks(20);
		int n = 0;
		for (int slot = 0; slot < 36; slot++) if (t.player().item(slot).equals("minecraft:" + drop)) n += t.player().count(slot);
		return n;
	}

	@Test(name = "silk_glass", tags = "world")
	public void silkGlass(TestContext t) {
		// Glass mines no faster with a pickaxe, but only Silk Touch drops it: the pickaxe is brought in for it.
		int n = silkDrop(t, "glass", "glass", true, false);
		t.check(n == 1 && t.arena().is(3, 0, 0, "air"), n + " glass back from mining it");
		t.note(n + " glass back from mining it");
	}

	@Test(name = "shears_glow_lichen", tags = "world")
	public void shearsGlowLichen(TestContext t) {
		// Glow lichen breaks at once and drops only to shears (Silk Touch doesn't, in Java): the shears in the inventory
		// are brought in for it, mining it faster than a hand, past the Silk Touch pickaxe.
		t.player().give(16, "shears");
		// Flat on the floor: looked at low, eyes (0.5, 1.62, 0.5) to (3.5, 0.03, 0.5).
		int n = silkDrop(t, "glow_lichen[down=true]", "glow_lichen", true, true, 27.9f);
		boolean gone = t.arena().is(3, 0, 0, "air");
		t.check(n == 1 && gone, n + " glow lichen back from mining it" + (gone ? "" : ", the lichen still there"));
		t.note(n + " glow lichen back from mining it");
	}

	@Test(name = "no_auto_tool", tags = "world")
	public void noAutoTool(TestContext t) {
		// Auto Tool off: Packet Mine mines with what you hold (a hand here), so the glass is gone and nothing drops.
		int n = silkDrop(t, "glass", "glass", false, false);
		t.check(n == 0 && t.arena().is(3, 0, 0, "air"), n + " glass back with Auto Tool off");
		t.note(n + " glass back with Auto Tool off");
	}
}
