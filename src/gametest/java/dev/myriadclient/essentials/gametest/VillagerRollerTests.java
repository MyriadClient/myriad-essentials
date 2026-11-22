package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/**
 * Villager Roller: a librarian selling the wanted book is locked in (one trade bought) and left; one that doesn't is
 * rerolled (its lectern broken, put back, and its trades new).
 */
public final class VillagerRollerTests extends EssentialsSuite {
	private static final String ROLLER = "myriad-essentials:villager_roller";

	/**
	 * A librarian at the arena's (2, 0, 2), never traded with, selling a book of {@code enchantment} and buying paper;
	 * {@code ai} off, it keeps those trades (with it, it may drop the job it has no workstation memory for and take the
	 * lectern again, with new trades).
	 */
	private static void librarian(TestContext t, String enchantment, boolean ai) {
		String offers = "{Recipes:[{buy:{id:\"minecraft:paper\",count:24},sell:{id:\"minecraft:emerald\",count:1},maxUses:16,xp:2},"
			+ "{buy:{id:\"minecraft:emerald\",count:5},buyB:{id:\"minecraft:book\",count:1},sell:{id:\"minecraft:enchanted_book\",count:1,"
			+ "components:{\"minecraft:stored_enchantments\":{levels:{\"" + enchantment + "\":1}}}},maxUses:12,xp:1}]}";
		t.server().run("summon villager " + t.arena().at(2.5, 0, 2.5) + " {VillagerData:{profession:\"minecraft:librarian\",level:1,type:\"minecraft:plains\"},"
			+ "Offers:" + offers + ",Xp:0,PersistenceRequired:1b" + (ai ? "" : ",NoAI:1b") + "}");
	}

	private static String villager(TestContext t, String path) {
		return t.server().run("data get entity @e[type=villager,limit=1,sort=nearest," + "x=" + t.arena().x(2.5) + ",y=" + t.arena().y(0) + ",z=" + t.arena().z(2.5)
			+ "] " + path);
	}

	@Test(name = "locks_found", tags = "automation", timeoutTicks = 600)
	public void locksFound(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.arena().set(2, 0, 0, "lectern");
		t.player().at(1.5, 0, 1.5, 180, 20);
		t.player().give(9, "paper", 24);
		librarian(t, "minecraft:mending", false);
		t.module(ROLLER).set("enchantment", "minecraft:mending").set("lock_trade", "true");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(ROLLER).enable();
		boolean off = t.waitUntil("Villager Roller to stop", () -> !t.onGame(() -> t.module(ROLLER).get().isEnabled()), 300);
		String xp = villager(t, "Xp");
		boolean emerald = t.server().passes("if items entity " + t.player().name() + " container.* emerald");
		String detail = String.format(Locale.ROOT, "%s; villager xp: %s; emerald %s", off ? "stopped" : "still on", xp.replaceAll(".*: ", ""), emerald ? "got" : "not got");
		t.module(ROLLER).disable();
		t.server().run("kill @e[type=villager]");
		t.check(off && emerald && !xp.endsWith(" 0"), detail);
		t.note(detail);
	}

	@Test(name = "rerolls", tags = "automation", timeoutTicks = 2400)
	public void rerolls(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=item]");
		t.arena().set(2, 0, 0, "lectern");
		t.player().at(1.5, 0, 1.5, 180, 20);
		t.player().give(0, "diamond_axe");
		librarian(t, "minecraft:unbreaking", true);
		t.module(ROLLER).set("enchantment", "minecraft:mending").set("lock_trade", "false");
		t.waitTicks(20);
		t.flagsFromHere();
		String before = villager(t, "Offers.Recipes");
		t.module(ROLLER).enable();
		// One roll: the lectern broken and back, the villager a librarian again with other trades.
		boolean broke = t.waitUntil("the lectern broken", () -> !t.arena().is(2, 0, 0, "lectern"), 300);
		boolean back = broke && t.waitUntil("the lectern back", () -> t.arena().is(2, 0, 0, "lectern"), 900);
		boolean rerolled = back && t.waitUntil("new trades", () -> !villager(t, "Offers.Recipes").equals(before), 900);
		t.module(ROLLER).disable();
		// Untouched: the villager is talked to and its lectern broken, never hit.
		String health = villager(t, "Health").replaceAll(".*: ", "").replace("f", "");
		boolean unhurt = health.startsWith("20");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "lectern %s, %s, trades %s, villager health %s", broke ? "broken" : "not broken", back ? "back" : "not back",
			rerolled ? "rerolled" : "the same", health);
		t.check(broke && back && rerolled && unhurt, detail);
		t.note(detail);
	}
}
