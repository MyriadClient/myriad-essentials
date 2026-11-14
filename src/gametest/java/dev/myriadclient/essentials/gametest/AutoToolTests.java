package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

/** Auto Tool: picking a tool without wearing out one that's nearly broken. */
public final class AutoToolTests extends EssentialsSuite {
	/** The damage on the item in {@code slot}; -1 if the slot is empty. */
	private static int damage(TestContext t, int slot) {
		return t.player().item(slot).isEmpty() ? -1 : t.player().damage(slot);
	}

	@Test(tags = "inventory")
	public void preserve(TestContext t) {
		// Only a pickaxe with 6 uses left in the hotbar and a fresh one in the inventory: Packet Mine (picking the tool) mines
		// with the fresh one, brought in, and leaves the worn one alone.
		t.player().give(0, "netherite_pickaxe[damage=2025]").give(14, "diamond_pickaxe");
		t.arena().set(3, 0, 0, "deepslate");
		t.player().select(1);
		t.module("myriad-essentials:auto_tool").enable();
		t.module("myriad-essentials:packet_mine").enable();
		t.player().look(-90, 20.47f);
		t.waitTicks(2);
		t.step("attack");
		t.waitTicks(60);
		t.module("myriad-essentials:packet_mine").disable();
		t.module("myriad-essentials:auto_tool").disable();
		int worn = t.player().item(0).equals("minecraft:netherite_pickaxe") ? damage(t, 0) : damage(t, 14);
		boolean mined = t.arena().is(3, 0, 0, "air");
		String detail = "worn pickaxe damage " + worn + ", deepslate " + (mined ? "mined" : "not mined");
		t.check(worn == 2025 && mined, detail);
		t.note(detail);
	}
}
