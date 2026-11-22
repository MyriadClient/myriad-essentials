package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/**
 * Auto Trader, everything in reach (no Baritone in the dev client): emerald blocks from the input chest crafted into
 * emeralds, spent at a cleric, what it sells put into the output chest, and done once the input is empty.
 */
public final class AutoTraderTests extends EssentialsSuite {
	private static final String TRADER = "myriad-essentials:auto_trader";

	private static int countIn(TestContext t, int x, int y, int z, String item) {
		int n = 0;
		for (String s : t.arena().items(x, y, z, 27)) if (s.startsWith(item + " x")) n += Integer.parseInt(s.substring(item.length() + 2));
		return n;
	}

	@Test(name = "sold_out_waits", tags = "automation", timeoutTicks = 1200)
	public void soldOutWaits(TestContext t) {
		// A cleric with five bottles to sell: five bought and put away, then it waits on (for the restock) with the rest.
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=villager]");
		t.arena().set(2, 0, -1, "chest{Items:[{Slot:0b,id:\"minecraft:emerald_block\",count:2}]}");
		t.arena().set(-2, 0, -1, "chest");
		t.player().at(0.5, 0, 0.5, 0, 20);
		String offers = "{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:experience_bottle\",count:1},maxUses:5,xp:1}]}";
		t.server().run("summon villager " + t.arena().at(2.5, 0, 2.5) + " {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:cleric\",level:4,"
			+ "type:\"minecraft:plains\"},Offers:" + offers + "}");
		String in = t.arena().x(2) + " " + t.arena().y(0) + " " + t.arena().z(-1);
		String out = t.arena().x(-2) + " " + t.arena().y(0) + " " + t.arena().z(-1);
		t.module(TRADER).set("input_chest", in).set("output_chest", out).set("profession", "minecraft:cleric").set("trade", "minecraft:experience_bottle");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean five = t.waitUntil("five bottles put away", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 5, 600);
		t.waitTicks(60);
		boolean on = t.onGame(() -> t.module(TRADER).get().isEnabled());
		int bottles = countIn(t, -2, 0, -1, "experience_bottle");
		t.module(TRADER).disable();
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%d bottles put away, %s", bottles, on ? "waiting for the restock" : "stopped");
		t.check(five && bottles == 5 && on, detail);
		t.note(detail);
	}

	/** Puts a villager selling {@code recipes} at the arena's (2, 0, 2), the input chest at (2, 0, -1) holding {@code items}, the output at (-2, 0, -1). */
	private static void scene(TestContext t, String profession, String recipes, String items) {
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=villager]");
		t.arena().set(2, 0, -1, "chest{Items:[" + items + "]}");
		t.arena().set(-2, 0, -1, "chest");
		t.player().at(0.5, 0, 0.5, 0, 20);
		t.server().run("summon villager " + t.arena().at(2.5, 0, 2.5) + " {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:" + profession
			+ "\",level:4,type:\"minecraft:plains\"},Offers:{Recipes:[" + recipes + "]}}");
		String in = t.arena().x(2) + " " + t.arena().y(0) + " " + t.arena().z(-1);
		String out = t.arena().x(-2) + " " + t.arena().y(0) + " " + t.arena().z(-1);
		t.module(TRADER).set("input_chest", in).set("output_chest", out).set("profession", "minecraft:" + profession);
	}

	@Test(name = "sells_for_emeralds", tags = "automation", timeoutTicks = 1200)
	public void sellsForEmeralds(TestContext t) {
		// A farmer buying 20 carrots an emerald, 100 carrots in the input: 5 emeralds out, nothing left to pay with.
		scene(t, "farmer", "{buy:{id:\"minecraft:carrot\",count:20},sell:{id:\"minecraft:emerald\",count:1},maxUses:9999,xp:1}",
			"{Slot:0b,id:\"minecraft:carrot\",count:64},{Slot:1b,id:\"minecraft:carrot\",count:36},{Slot:2b,id:\"minecraft:wheat\",count:5}");
		t.module(TRADER).set("trade", "minecraft:emerald");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean done = t.waitUntil("Auto Trader to finish", () -> !t.onGame(() -> t.module(TRADER).get().isEnabled()), 1000);
		t.module(TRADER).disable();
		int emeralds = countIn(t, -2, 0, -1, "emerald"), carrots = countIn(t, 2, 0, -1, "carrot"), wheat = countIn(t, 2, 0, -1, "wheat");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d emeralds out, %d carrots and %d wheat left in the input", done ? "finished" : "still going", emeralds, carrots, wheat);
		t.check(done && emeralds == 5 && carrots == 0 && wheat == 5, detail);
		t.note(detail);
	}

	@Test(name = "book_and_emeralds", tags = "automation", timeoutTicks = 1200)
	public void bookAndEmeralds(TestContext t) {
		// A librarian selling Mending for 5 emeralds and a book: 3 books and an emerald block in, 3 books out (1 block = 9
		// emeralds, enough for one, so 1 block + 6 emeralds), the spare emerald left.
		scene(t, "librarian", "{buy:{id:\"minecraft:emerald\",count:5},buyB:{id:\"minecraft:book\",count:1},sell:{id:\"minecraft:enchanted_book\",count:1,"
				+ "components:{\"minecraft:stored_enchantments\":{levels:{\"minecraft:mending\":1}}}},maxUses:12,xp:1}",
			"{Slot:0b,id:\"minecraft:emerald_block\",count:1},{Slot:1b,id:\"minecraft:emerald\",count:7},{Slot:2b,id:\"minecraft:book\",count:3}");
		t.module(TRADER).set("trade", "minecraft:enchanted_book").set("enchantment", "minecraft:mending");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean done = t.waitUntil("Auto Trader to finish", () -> !t.onGame(() -> t.module(TRADER).get().isEnabled()), 1000);
		t.module(TRADER).disable();
		int books = countIn(t, -2, 0, -1, "enchanted_book"), left = countIn(t, 2, 0, -1, "book");
		boolean spare = t.server().passes("if items entity " + t.player().name() + " container.* emerald");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d Mending books out, %d books left in the input, spare emerald %s", done ? "finished" : "still going",
			books, left, spare ? "on you" : "gone");
		t.check(done && books == 3 && left == 0, detail);
		t.note(detail);
	}

	@Test(name = "waits_for_hopper", tags = "automation", timeoutTicks = 2400)
	public void waitsForHopper(TestContext t) {
		// The input fed by a hopper: emptied, it isn't done; more emeralds dropped into the hopper are fetched and spent.
		scene(t, "cleric", "{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:experience_bottle\",count:1},maxUses:9999,xp:1}",
			"{Slot:0b,id:\"minecraft:emerald\",count:4}");
		t.arena().set(2, 1, -1, "hopper[facing=down]");
		t.module(TRADER).set("trade", "minecraft:experience_bottle");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean first = t.waitUntil("the first 4 bottles put away", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 4, 600);
		t.waitTicks(40);
		boolean waiting = t.onGame(() -> t.module(TRADER).get().isEnabled());
		t.server().run("item replace block " + t.arena().x(2) + " " + t.arena().y(1) + " " + t.arena().z(-1) + " container.0 with emerald 3");
		boolean more = t.waitUntil("3 more bottles", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 7, 900);
		boolean still = t.onGame(() -> t.module(TRADER).get().isEnabled());
		t.module(TRADER).disable();
		int bottles = countIn(t, -2, 0, -1, "experience_bottle");
		t.server().run("kill @e[type=villager]");
		t.arena().set(2, 1, -1, "air");
		String detail = String.format(Locale.ROOT, "%d bottles; %s once empty, %s after the refill", bottles, waiting ? "waited" : "stopped", still ? "still waiting" : "stopped");
		t.check(first && waiting && more && still && bottles == 7, detail);
		t.note(detail);
	}

	@Test(name = "buys_into_output", tags = "automation", timeoutTicks = 1200)
	public void buysIntoOutput(TestContext t) {
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=villager]");
		t.arena().set(2, 0, -1, "chest{Items:[{Slot:0b,id:\"minecraft:emerald_block\",count:2}]}");
		t.arena().set(-2, 0, -1, "chest");
		t.player().at(0.5, 0, 0.5, 0, 20);
		String offers = "{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:experience_bottle\",count:1},maxUses:9999,xp:1}]}";
		t.server().run("summon villager " + t.arena().at(2.5, 0, 2.5) + " {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:cleric\",level:4,"
			+ "type:\"minecraft:plains\"},Offers:" + offers + "}");
		String in = t.arena().x(2) + " " + t.arena().y(0) + " " + t.arena().z(-1);
		String out = t.arena().x(-2) + " " + t.arena().y(0) + " " + t.arena().z(-1);
		t.module(TRADER).set("input_chest", in).set("output_chest", out).set("profession", "minecraft:cleric").set("trade", "minecraft:experience_bottle");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean done = t.waitUntil("Auto Trader to finish", () -> !t.onGame(() -> t.module(TRADER).get().isEnabled()), 1000);
		t.module(TRADER).disable();
		int bottles = countIn(t, -2, 0, -1, "experience_bottle"), blocks = countIn(t, 2, 0, -1, "emerald_block");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d bottles in the output chest, %d emerald blocks left in the input", done ? "finished" : "still going", bottles, blocks);
		t.check(done && bottles == 18 && blocks == 0, detail);
		t.note(detail);
	}
}
