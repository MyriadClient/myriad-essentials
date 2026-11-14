package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

/** Offhand: a totem into the off hand from the inventory or the hotbar, standing and at a sprint. */
public final class OffhandTests extends EssentialsSuite {
	private static final int OFF_HAND = 40;

	/** What the off hand holds on the server, for the detail line. */
	private static String offHand(TestContext t) {
		String held = t.player().item(OFF_HAND);
		return held.isEmpty() ? "nothing" : held;
	}

	@Test(tags = "inventory")
	public void offhand(TestContext t) {
		t.player().give(14, "totem_of_undying");
		t.module("myriad-essentials:offhand").enable();
		t.waitTicks(20);
		boolean ok = t.player().item(OFF_HAND).equals("minecraft:totem_of_undying");
		String detail = "off hand holds " + offHand(t);
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "inventory")
	public void sprinting(TestContext t) {
		// Grim cancels a click while you sprint (and, from 1.21.2, while you walk) without telling the client: the totem has
		// to wait for a still tick, or the client shows one the server doesn't have.
		t.player().give(14, "totem_of_undying");
		t.hold(Key.FORWARD, Key.SPRINT).forTicksThenContinue(40);
		t.waitTicks(5);
		t.module("myriad-essentials:offhand").enable();
		t.waitTicks(35);
		boolean ok = t.player().item(OFF_HAND).equals("minecraft:totem_of_undying") && t.player().item(14).isEmpty();
		String detail = "off hand holds " + offHand(t);
		t.check(ok, detail);
		t.note(detail);
	}

	@Test(tags = "inventory")
	public void hotbarSprinting(TestContext t) {
		// From the hotbar it goes over with the swap-hands key, which isn't a click: no waiting.
		t.player().give(4, "totem_of_undying");
		t.hold(Key.FORWARD, Key.SPRINT).forTicksThenContinue(40);
		t.waitTicks(5);
		t.module("myriad-essentials:offhand").enable();
		t.waitTicks(35);
		String slot = t.player().item(4);
		boolean ok = t.player().item(OFF_HAND).equals("minecraft:totem_of_undying") && slot.isEmpty();
		String detail = "off hand holds " + offHand(t) + ", hotbar slot 5 " + (slot.isEmpty() ? "empty" : slot);
		t.check(ok, detail);
		t.note(detail);
	}
}
