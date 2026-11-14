package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Kill Aura: a husk killed with the sword in hand or one switched to from the inventory, and hits while you walk. */
public final class KillAuraTests extends EssentialsSuite {
	private static final Pattern HAND = Pattern.compile("item=(\\S+)");

	/** The arena's husks tagged "suite" (only the arena's: another client's tests may have husks of their own). */
	private static String husks(TestContext t) {
		return "@e[tag=suite,x=" + t.arena().x(0) + ",y=" + t.arena().y(0) + ",z=" + t.arena().z(0) + ",distance=..64]";
	}

	@Test(tags = "combat")
	public void holding(TestContext t) {
		t.player().give(0, "diamond_sword");
		t.server().run("summon husk " + t.arena().at(3.5, 0, 0.5) + " {NoAI:1b,Health:10f,Tags:[\"suite\"]}");
		t.waitTicks(10);
		t.player().select(0);
		t.module("myriad-essentials:kill_aura").set("weapon", "holding").enable();
		t.waitTicks(60);
		t.module("myriad-essentials:kill_aura").disable();
		boolean killed = t.server().passes("unless entity " + husks(t));
		t.check(killed, "husk killed with the held sword");
		t.note("husk killed with the held sword");
	}

	@Test(name = "switch", tags = "combat")
	public void switchWeapon(TestContext t) {
		t.player().give(13, "diamond_sword").give(0, "dirt", 16);
		t.server().run("summon husk " + t.arena().at(3.5, 0, 0.5) + " {NoAI:1b,Health:10f,Tags:[\"suite\"]}");
		t.waitTicks(10);
		t.player().select(0);
		t.module("myriad-essentials:kill_aura").set("weapon", "switch").enable();
		t.waitTicks(80);
		Matcher shown = HAND.matcher(String.join(" ", t.step("status")));
		boolean showed = shown.find();
		t.module("myriad-essentials:kill_aura").disable();
		t.waitTicks(30);
		boolean killed = t.server().passes("unless entity " + husks(t));
		boolean back = t.player().item(13).equals("minecraft:diamond_sword");
		String hand = showed ? shown.group(1) : "?";
		String detail = "killed=" + killed + ", hand showed " + hand + ", sword back in inventory=" + back;
		t.check(killed && back && showed && hand.endsWith("dirt"), detail);
		t.note(detail);
	}

	// Flaky in the old suite: flags Simulation now and then with three clients running (load); passes alone.
	@Test(tags = "combat")
	public void walking(TestContext t) {
		// Husks beside the path, so it turns you while you walk diagonally: walking follows the sent yaw (the move fix),
		// which as 1.20.4 has to move exactly as that version did (a diagonal isn't normalised there).
		t.player().give(0, "diamond_sword");
		for (int x = 4; x < 30; x += 4) {
			t.server().run("summon husk " + t.arena().at(x + 0.5, 0, 2.5)
				+ " {NoAI:1b,Health:1000f,attributes:[{id:\"minecraft:max_health\",base:1000}],Tags:[\"suite\"]}");
		}
		t.waitTicks(10);
		t.flagsFromHere();
		t.player().select(0);
		t.module("myriad-essentials:kill_aura").set("weapon", "holding").enable();
		t.hold(Key.FORWARD, Key.SPRINT).forTicks(80);
		t.waitTicks(1);
		t.module("myriad-essentials:kill_aura").disable();
		double x = t.player().serverPosition().x;
		String detail = String.format(Locale.ROOT, "walked to x=%.1f beside the husks", x);
		t.check(x > 12, detail);
		t.note(detail);
	}
}
