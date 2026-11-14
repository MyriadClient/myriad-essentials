package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Portal from Enable, with the obsidian and the flint and steel only in the inventory and the hotbar full: a lit
 * portal in front of you, and the hotbar and off hand as they were. Built 8000 blocks out along x, away from the arena;
 * a nether portal lights only in the overworld or the Nether.
 */
public final class AutoPortalTests extends EssentialsSuite {
	private static final int X = 8000, Y = 0, Z = 0;
	private static final List<String> FULL_HOTBAR = List.of("diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "bow", "shield",
		"golden_apple", "totem_of_undying", "shears");

	private static void portalPass(TestContext t, boolean air) {
		var a = t.arena();
		a.forceload(X - 8, Z - 8, X + 8, Z + 8);
		a.fill(X - 6, Y - 1, Z - 6, X + 6, Y - 1, Z + 6, "stone");
		a.fill(X - 6, Y, Z - 6, X + 6, Y + 6, Z + 6, "air");
		t.server().run("kill @e[type=item,x=" + a.x(X) + ",y=" + a.y(Y) + ",z=" + a.z(Z) + ",distance=..20]");
		t.player().give(40, "totem_of_undying");
		for (int i = 0; i < FULL_HOTBAR.size(); i++) t.player().give(i, FULL_HOTBAR.get(i));
		t.player().give(19, "obsidian", 16).give(20, "flint_and_steel");
		t.player().at(X + 0.5, Y, Z + 0.5, -90, 30);
		t.module("myriad-essentials:auto_portal").set("trigger", "enable").set("rotate", "false").set("corners", String.valueOf(!air))
			.set("air_place", String.valueOf(air)).set("light", "true");
		t.waitTicks(5);
		t.server().likeTwoBTwoT();
		t.flagsFromHere();
		// On the floor block you look at, across the view: a 2 by 3 portal somewhere in front.
		t.module("myriad-essentials:auto_portal").enable();
		t.waitTicks(140);
		t.module("myriad-essentials:auto_portal").disable();
		t.waitTicks(60);
		boolean lit = t.server().run("fill " + a.block(X + 1, Y, Z - 3) + " " + a.block(X + 6, Y + 5, Z + 3) + " nether_portal replace nether_portal")
			.contains("filled 6 block");
		List<String> moved = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			String now = t.player().item(i).replace("minecraft:", "");
			if (!now.equals(FULL_HOTBAR.get(i))) moved.add(i + ": '" + now + "'");
		}
		String offhand = t.player().item(40).replace("minecraft:", "");
		String detail = (lit ? "lit" : "not lit") + ", hotbar " + (moved.isEmpty() ? "as it was" : "{" + String.join(", ", moved) + "}") + ", off hand "
			+ (offhand.isEmpty() ? "empty" : offhand);
		t.check(lit && moved.isEmpty() && offhand.equals("totem_of_undying"), detail);
		t.note(detail);
	}

	@Test(tags = {"world", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void fullHotbar(TestContext t) {
		portalPass(t, false);
	}

	@Test(tags = {"world", "2b2t"}, allowFlags = {"RotationPlace", "AirLiquidPlace", "PositionPlace"})
	public void airPlace(TestContext t) {
		portalPass(t, true);
	}
}
