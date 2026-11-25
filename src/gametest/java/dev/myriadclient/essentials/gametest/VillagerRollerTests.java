package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/**
 * Villager Roller: a librarian selling the wanted book is locked in (one trade bought) and left; one that doesn't is
 * rerolled (its lectern broken, put back, and its trades new). So is one asking more than Lowest Price allows, or whose
 * other first trade isn't the Second Trade wanted.
 */
public final class VillagerRollerTests extends EssentialsSuite {
	private static final String ROLLER = "myriad-essentials:villager_roller";

	/**
	 * A librarian at the arena's (2, 0, 2), never traded with, selling a book of {@code enchantment} and buying paper;
	 * {@code ai} off, it keeps those trades (with it, it may drop the job it has no workstation memory for and take the
	 * lectern again, with new trades).
	 */
	private static void librarian(TestContext t, String enchantment, boolean ai) {
		librarian(t, enchantment, ai, 5);
	}

	/** The same, the book costing {@code price} emeralds (Mending's lowest is 10). */
	private static void librarian(TestContext t, String enchantment, boolean ai, int price) {
		librarian(t, enchantment, 1, ai, price);
	}

	/** The same, the book at {@code level}. */
	private static void librarian(TestContext t, String enchantment, int level, boolean ai, int price) {
		String offers = "{Recipes:[{buy:{id:\"minecraft:paper\",count:24},sell:{id:\"minecraft:emerald\",count:1},maxUses:16,xp:2},"
			+ "{buy:{id:\"minecraft:emerald\",count:" + price + "},buyB:{id:\"minecraft:book\",count:1},sell:{id:\"minecraft:enchanted_book\",count:1,"
			+ "components:{\"minecraft:stored_enchantments\":{levels:{\"" + enchantment + "\":" + level + "}}}},maxUses:12,xp:1}]}";
		// In a minecart beside the lectern: its AI on (it levels up, and takes the lectern again), but it can't walk off.
		t.server().run("summon minecart " + t.arena().at(2.5, 0, 1.5) + " {Passengers:[{id:\"minecraft:villager\",VillagerData:{profession:"
			+ "\"minecraft:librarian\",level:1,type:\"minecraft:plains\"},Offers:" + offers + ",Xp:0,PersistenceRequired:1b" + (ai ? "" : ",NoAI:1b") + "}]}");
	}

	private static String villager(TestContext t, String path) {
		return t.server().run("data get entity @e[type=villager,limit=1,sort=nearest," + "x=" + t.arena().x(2.5) + ",y=" + t.arena().y(0) + ",z=" + t.arena().z(1.5)
			+ "] " + path);
	}

	@Test(name = "locks_found", tags = "automation", timeoutTicks = 600)
	public void locksFound(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
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
		t.server().run("kill @e[type=minecart]");
		t.check(off && emerald && !xp.endsWith(" 0"), detail);
		t.note(detail);
	}

	@Test(name = "rerolls", tags = "automation", timeoutTicks = 2400)
	public void rerolls(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
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
		t.server().run("kill @e[type=minecart]");
		String detail = String.format(Locale.ROOT, "lectern %s, %s, trades %s, villager health %s", broke ? "broken" : "not broken", back ? "back" : "not back",
			rerolled ? "rerolled" : "the same", health);
		t.check(broke && back && rerolled && unhurt, detail);
		t.note(detail);
	}

	/** Lowest Price: a Mending book at 10 emeralds, its lowest, is found and the villager left as it is. */
	@Test(name = "lowest_price_found", tags = "automation", timeoutTicks = 600)
	public void lowestPriceFound(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		t.arena().set(2, 0, 0, "lectern");
		t.player().at(1.5, 0, 1.5, 180, 20);
		librarian(t, "minecraft:mending", false, 10);
		t.module(ROLLER).set("enchantment", "minecraft:mending").set("lock_trade", "false").set("price", "LOWEST").set("second_trade", "ANY");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(ROLLER).enable();
		boolean off = t.waitUntil("Villager Roller to stop", () -> !t.onGame(() -> t.module(ROLLER).get().isEnabled()), 300);
		boolean lectern = t.arena().is(2, 0, 0, "lectern");
		t.module(ROLLER).disable();
		t.module(ROLLER).set("price", "ANY");
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		String detail = (off ? "stopped" : "still on") + ", lectern " + (lectern ? "kept" : "broken");
		t.check(off && lectern, detail);
		t.note(detail);
	}

	/** Lowest Price: the same book at 20 emeralds is rolled again (its lectern broken). */
	@Test(name = "lowest_price_rerolls", tags = "automation", timeoutTicks = 600)
	public void lowestPriceRerolls(TestContext t) {
		rerollsFor(t, 20, "price", "LOWEST", "ANY");
	}

	/** Second Trade Bookshelf: a librarian buying paper beside the wanted book is rolled again. */
	@Test(name = "second_trade_rerolls", tags = "automation", timeoutTicks = 600)
	public void secondTradeRerolls(TestContext t) {
		rerollsFor(t, 10, "price", "ANY", "BOOKSHELF");
	}

	/** A librarian selling Mending at {@code price}, with {@code setting} at {@code value} and Second Trade at {@code second}: its lectern is broken. */
	private void rerollsFor(TestContext t, int price, String setting, String value, String second) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		t.server().run("kill @e[type=item]");
		t.arena().set(2, 0, 0, "lectern");
		t.player().at(1.5, 0, 1.5, 180, 20);
		t.player().give(0, "diamond_axe");
		librarian(t, "minecraft:mending", false, price);
		t.module(ROLLER).set("enchantment", "minecraft:mending").set("lock_trade", "false").set(setting, value).set("second_trade", second);
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(ROLLER).enable();
		boolean broke = t.waitUntil("the lectern broken", () -> !t.arena().is(2, 0, 0, "lectern"), 300);
		t.module(ROLLER).disable();
		t.module(ROLLER).set("price", "ANY").set("second_trade", "ANY");
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		t.server().run("kill @e[type=item]");
		t.arena().set(2, 0, 0, "lectern");
		String detail = "lectern " + (broke ? "broken" : "not broken");
		t.check(broke, detail);
		t.note(detail);
	}

	/** Keep Level V Under: Efficiency V at 15 is kept though it's not the book wanted and the other trade is paper, not bookshelves. */
	@Test(name = "keeps_cheap_five", tags = "automation", timeoutTicks = 600)
	public void keepsCheapFive(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		t.arena().set(2, 0, 0, "lectern");
		t.player().at(1.5, 0, 1.5, 180, 20);
		librarian(t, "minecraft:efficiency", 5, false, 15);
		t.module(ROLLER).set("enchantment", "minecraft:mending").set("lock_trade", "false").set("second_trade", "BOOKSHELF")
			.set("keep_level_v_under", "18").set("level_up_to", "1");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(ROLLER).enable();
		boolean off = t.waitUntil("Villager Roller to stop", () -> !t.onGame(() -> t.module(ROLLER).get().isEnabled()), 300);
		boolean lectern = t.arena().is(2, 0, 0, "lectern");
		t.module(ROLLER).disable();
		t.module(ROLLER).set("second_trade", "ANY").set("keep_level_v_under", "0");
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		String detail = (off ? "stopped" : "still on") + ", lectern " + (lectern ? "kept" : "broken");
		t.check(off && lectern, detail);
		t.note(detail);
	}

	/** Level Up To 2: locked in, then sold paper until it's a level 2 librarian. */
	@Test(name = "levels_up", tags = "automation", timeoutTicks = 1600)
	public void levelsUp(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		t.arena().set(2, 0, 0, "lectern");
		t.player().at(1.5, 0, 1.5, 180, 20);
		t.player().give(9, "paper", 64);
		t.player().give(10, "paper", 64);
		t.player().give(11, "paper", 64);
		// Its AI off while it's found and locked in (with it, a villager with no workstation memory may drop the job and
		// take the lectern again, with other trades), then on: it only levels up with it, and once traded keeps its job.
		librarian(t, "minecraft:mending", false);
		t.module(ROLLER).set("enchantment", "minecraft:mending").set("lock_trade", "true").set("level_up_to", "2");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(ROLLER).enable();
		boolean locked = t.waitUntil("it locked in", () -> !villager(t, "Xp").endsWith(" 0"), 300);
		t.server().run("data merge entity @e[type=villager,limit=1,sort=nearest,x=" + t.arena().x(2.5) + ",y=" + t.arena().y(0) + ",z=" + t.arena().z(1.5) + "] {NoAI:0b}");
		boolean off = locked && t.waitUntil("Villager Roller to stop", () -> !t.onGame(() -> t.module(ROLLER).get().isEnabled()), 1200);
		String level = villager(t, "VillagerData.level").replaceAll(".*: ", "");
		String xp = villager(t, "Xp").replaceAll(".*: ", "");
		boolean emerald = t.server().passes("if items entity " + t.player().name() + " container.* emerald");
		t.module(ROLLER).disable();
		t.module(ROLLER).set("level_up_to", "1");
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		String detail = (off ? "stopped" : "still on") + ", villager level " + level + ", xp " + xp + ", emeralds " + (emerald ? "got" : "none");
		t.check(off && level.equals("2"), detail);
		t.note(detail);
	}

	/** Min Level 4 with Any: Unbreaking III, at its highest, counts. */
	@Test(name = "min_level_takes_maxed", tags = "automation", timeoutTicks = 600)
	public void minLevelTakesMaxed(TestContext t) {
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		t.arena().set(2, 0, 0, "lectern");
		t.player().at(1.5, 0, 1.5, 180, 20);
		librarian(t, "minecraft:unbreaking", 3, false, 11);
		t.module(ROLLER).set("enchantment", "any").set("min_level", "4").set("lock_trade", "false").set("level_up_to", "1");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(ROLLER).enable();
		boolean off = t.waitUntil("Villager Roller to stop", () -> !t.onGame(() -> t.module(ROLLER).get().isEnabled()), 300);
		boolean lectern = t.arena().is(2, 0, 0, "lectern");
		t.module(ROLLER).disable();
		t.module(ROLLER).set("enchantment", "minecraft:mending").set("min_level", "0");
		t.server().run("kill @e[type=villager]");
		t.server().run("kill @e[type=minecart]");
		String detail = (off ? "stopped" : "still on") + ", lectern " + (lectern ? "kept" : "broken");
		t.check(off && lectern, detail);
		t.note(detail);
	}
}
