package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.GrimFlag;
import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Leaving and coming back: Auto Reconnect after a kick, Auto Disconnect at low health and next to a charged respawn
 * anchor, and Anti AFK's sneak tap when idle (none while walking). Every test ends back in the game.
 */
public final class ConnectionTests extends EssentialsSuite {
	/** Whether the server lists {@code name} as online. */
	private static boolean onServer(TestContext t, String name) {
		String list = t.server().run("list");
		int colon = list.indexOf(':');
		return Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "(?![\\w])").matcher(colon < 0 ? list : list.substring(colon + 1)).find();
	}

	/** Whether {@code name} leaves the server within {@code seconds}. */
	private static boolean leavesWithin(TestContext t, String name, int seconds) {
		long until = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < until) {
			if (!onServer(t, name)) return true;
			t.waitTicks(2);
		}
		return false;
	}

	/**
	 * Joins again, a minute at most (the server turns away a connection within 4 s of the last one: bukkit.yml's
	 * connection-throttle), then waits for the world around you; fails the test if it couldn't.
	 */
	private static void rejoin(TestContext t) {
		if (!t.player().rejoin(60)) t.check(false, "couldn't rejoin");
		t.waitTicks(60);
	}

	@Test(tags = "connection")
	public void autoReconnect(TestContext t) {
		// Kicked: back on the server after the delay (and the server's 4 s connection throttle).
		String name = t.player().name();
		t.module("myriad-essentials:auto_reconnect").set("delay", "2").enable();
		t.server().run("kick " + name + " suite kick");
		boolean back = false;
		long until = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < until) {
			t.waitTicks(20);
			if (onServer(t, name)) {
				back = true;
				break;
			}
		}
		t.waitTicks(60);
		t.module("myriad-essentials:auto_reconnect").disable();
		// Not back by itself: joined again here, so the next test starts in the game.
		if (!back) rejoin(t);
		t.check(back, back ? "back on the server" : "not back after 15 s");
		t.note("back on the server");
	}

	@Test(tags = "connection")
	public void antiAfk(TestContext t) {
		Minecraft mc = Minecraft.getInstance();
		// Watched every tick, since the delay is real time and a client in the background ticks slower: a tap is caught
		// whenever it comes within the ticks given.
		BooleanSupplier sneaking = () -> mc.player != null && mc.player.isShiftKeyDown();
		// Standing still for the delay (10 s): the sneak tap comes.
		t.module("myriad-essentials:anti_afk").set("swing", "true").set("sneak", "true").set("delay", "10");
		t.module("myriad-essentials:anti_afk").enable();
		boolean tapped = t.waitUntil("the sneak tap", sneaking, 300);
		t.module("myriad-essentials:anti_afk").disable();
		// Walking for longer than the delay, there and back: no tap.
		t.player().at(-17.5, 0, 0.5);
		t.flagsFromHere();
		t.module("myriad-essentials:anti_afk").enable();
		t.hold(Key.FORWARD).forTicksThenContinue(180);
		boolean tappedWalking = t.waitUntil("a sneak tap while walking there", sneaking, 180);
		if (!tappedWalking) {
			t.player().look(90, 0);
			t.hold(Key.FORWARD).forTicksThenContinue(180);
			tappedWalking = t.waitUntil("a sneak tap while walking back", sneaking, 180);
		}
		t.hold().forTicks(0);
		t.module("myriad-essentials:anti_afk").disable();
		boolean stayed = !tappedWalking;
		List<String> bad = t.flags().stream().map(GrimFlag::check).distinct().sorted().toList();
		String detail = "tapped when idle=" + tapped + ", none while walking=" + stayed + ", flags=" + bad;
		t.check(tapped && stayed && bad.isEmpty(), detail);
		t.note(detail);
	}

	@Test(tags = "connection")
	public void autoDisconnect(TestContext t) {
		// Leaves on a hit below the health threshold, and next to a charged respawn anchor that could kill you (its blast
		// reaches you once the anchor's gone, which the danger maths has to know); rejoins after each.
		String name = t.player().name();
		var ad = t.module("myriad-essentials:auto_disconnect");
		for (String trigger : List.of("totem_pops", "totems_left", "armor", "players", "beds", "crystals", "creepers")) ad.set(trigger, "false");
		ad.set("health", "true").set("threshold", "6").set("anchors", "false").set("after_leaving", "turn_off").enable();
		t.waitTicks(10);
		t.server().run("damage " + name + " 15");
		boolean hurt = leavesWithin(t, name, 3);
		rejoin(t);
		// As a fresh start, but not healed: the anchor is judged against the health left after the hit, as before.
		t.server().run("clear " + name);
		t.server().run("effect clear " + name);
		t.server().run("gamemode survival " + name);
		t.arena().reset();
		t.player().at(0.5, 0, 0.5);
		t.server().run("effect give " + name + " saturation 1 20 true");
		t.flagsFromHere();
		t.arena().set(3, 0, 0, "respawn_anchor[charges=4]");
		ad.set("health", "false").set("anchors", "true").enable();
		t.waitTicks(10);
		boolean anchor = leavesWithin(t, name, 3);
		rejoin(t);
		String detail = "left at low health: " + hurt + ", left next to a charged anchor: " + anchor;
		t.check(hurt && anchor, detail);
		t.note(detail);
	}
}
