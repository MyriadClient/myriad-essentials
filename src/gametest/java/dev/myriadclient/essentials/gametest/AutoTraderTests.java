package dev.myriadclient.essentials.gametest;

import dev.myriadclient.essentials.modules.automation.AutoTrader;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.Locale;

/**
 * Auto Trader, everything in reach (no Baritone in the dev client): emerald blocks from the input chest crafted into
 * emeralds, spent at a cleric, what it sells put into the output chest, then waiting (never off) once the input is empty.
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
		t.module(TRADER).set("inputs", in).set("outputs", out).set("profession", "minecraft:cleric").set("trade", "minecraft:experience_bottle");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean five = t.waitUntil("five bottles put away", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 5, 600);
		t.waitTicks(60);
		boolean on = t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting());
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
		t.module(TRADER).set("inputs", in).set("outputs", out).set("profession", "minecraft:" + profession);
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
		boolean done = t.waitUntil("Auto Trader to wait for more", () -> t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting()), 1000);
		t.module(TRADER).disable();
		int emeralds = countIn(t, -2, 0, -1, "emerald"), carrots = countIn(t, 2, 0, -1, "carrot");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d emeralds out, %d carrots left in the input", done ? "waiting for more" : "still going", emeralds, carrots);
		t.check(done && emeralds == 5 && carrots == 0, detail);
		t.note(detail);
	}

	@Test(name = "two_of_each", tags = "automation", timeoutTicks = 1600)
	public void twoOfEach(TestContext t) {
		// The carrots split over two inputs (40 and 60), the first output full of dirt: all 100 sold, 5 emeralds into the
		// second output.
		scene(t, "farmer", "{buy:{id:\"minecraft:carrot\",count:20},sell:{id:\"minecraft:emerald\",count:1},maxUses:9999,xp:1}",
			"{Slot:0b,id:\"minecraft:carrot\",count:40}");
		t.arena().set(2, 0, -3, "chest{Items:[{Slot:0b,id:\"minecraft:carrot\",count:60}]}");
		StringBuilder dirt = new StringBuilder();
		for (int i = 0; i < 27; i++) dirt.append(i == 0 ? "" : ",").append("{Slot:").append(i).append("b,id:\"minecraft:dirt\",count:64}");
		t.arena().set(-2, 0, -3, "chest{Items:[" + dirt + "]}");
		var a = t.arena();
		t.module(TRADER).set("trade", "minecraft:emerald")
			.set("inputs", a.x(2) + " " + a.y(0) + " " + a.z(-1) + "; " + a.x(2) + " " + a.y(0) + " " + a.z(-3))
			.set("outputs", a.x(-2) + " " + a.y(0) + " " + a.z(-3) + "; " + a.x(-2) + " " + a.y(0) + " " + a.z(-1));
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean done = t.waitUntil("Auto Trader to wait for more", () -> t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting()), 1400);
		t.module(TRADER).disable();
		int emeralds = countIn(t, -2, 0, -1, "emerald"), first = countIn(t, -2, 0, -3, "emerald");
		int carrots = countIn(t, 2, 0, -1, "carrot") + countIn(t, 2, 0, -3, "carrot");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d emeralds in the second output (%d in the full one), %d carrots left in the inputs",
			done ? "waiting for more" : "still going", emeralds, first, carrots);
		t.check(done && emeralds == 5 && first == 0 && carrots == 0, detail);
		t.note(detail);
	}

	@Test(name = "sells_to_any", tags = "automation", timeoutTicks = 1600)
	public void sellsToAny(TestContext t) {
		// Any villager, Trade Emerald, the input holding wheat, carrots and iron: the farmer takes the wheat and carrots
		// (not the potatoes it also buys), the armorer the iron, the librarian nearest (paper only) is left alone.
		scene(t, "farmer", "{buy:{id:\"minecraft:wheat\",count:20},sell:{id:\"minecraft:emerald\",count:1},maxUses:9999,xp:1},"
				+ "{buy:{id:\"minecraft:potato\",count:26},sell:{id:\"minecraft:emerald\",count:1},maxUses:9999,xp:1},"
				+ "{buy:{id:\"minecraft:carrot\",count:22},sell:{id:\"minecraft:emerald\",count:1},maxUses:9999,xp:1}",
			"{Slot:0b,id:\"minecraft:wheat\",count:40},{Slot:1b,id:\"minecraft:carrot\",count:44},{Slot:2b,id:\"minecraft:iron_ingot\",count:8}");
		t.server().run("summon villager " + t.arena().at(-1.5, 0, 2.5) + " {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:armorer\","
			+ "level:4,type:\"minecraft:plains\"},Offers:{Recipes:[{buy:{id:\"minecraft:iron_ingot\",count:4},sell:{id:\"minecraft:emerald\",count:1},maxUses:9999,xp:1}]}}");
		t.server().run("summon villager " + t.arena().at(1.5, 0, 1.5) + " {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:librarian\","
			+ "level:4,type:\"minecraft:plains\"},Offers:{Recipes:[{buy:{id:\"minecraft:paper\",count:24},sell:{id:\"minecraft:emerald\",count:1},maxUses:9999,xp:1}]}}");
		t.module(TRADER).set("profession", "Any").set("trade", "minecraft:emerald");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean done = t.waitUntil("Auto Trader to wait for more", () -> t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting()), 1400);
		t.module(TRADER).disable();
		int emeralds = countIn(t, -2, 0, -1, "emerald");
		boolean empty = t.arena().items(2, 0, -1, 27).stream().allMatch(String::isEmpty);
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d emeralds out (2 wheat, 2 carrots, 2 iron), input %s", done ? "waiting for more" : "still going", emeralds,
			empty ? "empty" : "not empty");
		t.check(done && emeralds == 6 && empty, detail);
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
		boolean done = t.waitUntil("Auto Trader to wait for more", () -> t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting()), 1000);
		t.module(TRADER).disable();
		int books = countIn(t, -2, 0, -1, "enchanted_book"), left = countIn(t, 2, 0, -1, "book");
		boolean spare = t.server().passes("if items entity " + t.player().name() + " container.* emerald");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d Mending books out, %d books left in the input, spare emerald %s", done ? "waiting for more" : "still going",
			books, left, spare ? "on you" : "gone");
		t.check(done && books == 3 && left == 0, detail);
		t.note(detail);
	}

	@Test(name = "waits_for_hopper", tags = "automation", timeoutTicks = 2400)
	public void waitsForHopper(TestContext t) {
		// The input fed by a hopper: emptied, it waits; more emeralds dropped into the hopper are fetched and spent.
		scene(t, "cleric", "{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:experience_bottle\",count:1},maxUses:9999,xp:1}",
			"{Slot:0b,id:\"minecraft:emerald\",count:4}");
		t.arena().set(2, 1, -1, "hopper[facing=down]");
		t.module(TRADER).set("trade", "minecraft:experience_bottle");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean first = t.waitUntil("the first 4 bottles put away", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 4, 600);
		t.waitTicks(40);
		boolean waiting = t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting());
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

	@Test(name = "refilled_by_hand", tags = "automation", timeoutTicks = 2400)
	public void refilledByHand(TestContext t) {
		// No hopper: emptied, it still waits on; emeralds put in the chest later are fetched and spent.
		scene(t, "cleric", "{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:experience_bottle\",count:1},maxUses:9999,xp:1}",
			"{Slot:0b,id:\"minecraft:emerald\",count:4}");
		t.module(TRADER).set("trade", "minecraft:experience_bottle");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean first = t.waitUntil("the first 4 bottles put away", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 4, 600);
		boolean waiting = t.waitUntil("Auto Trader to wait for more", () -> t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting()), 200);
		t.server().run("item replace block " + t.arena().x(2) + " " + t.arena().y(0) + " " + t.arena().z(-1) + " container.0 with emerald 3");
		boolean more = t.waitUntil("3 more bottles", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 7, 900);
		boolean still = t.onGame(() -> t.module(TRADER).get().isEnabled());
		t.module(TRADER).disable();
		int bottles = countIn(t, -2, 0, -1, "experience_bottle");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%d bottles; %s once empty, %s after the refill", bottles, waiting ? "waited" : "stopped", still ? "still on" : "stopped");
		t.check(first && waiting && more && still && bottles == 7, detail);
		t.note(detail);
	}

	@Test(name = "reach_past_the_server", tags = "automation", timeoutTicks = 1200)
	public void reachPastTheServer(TestContext t) {
		// Reach at 8, you 6 blocks from the chests: the server's reach is what counts, so Baritone walks you closer before
		// each open, rather than it clicking from where Reach says it reaches (the server ignores that, the chest stays shut).
		scene(t, "cleric", "{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:experience_bottle\",count:1},maxUses:9999,xp:1}",
			"{Slot:0b,id:\"minecraft:emerald\",count:4}");
		t.module(TRADER).set("trade", "minecraft:experience_bottle");
		t.module("myriad-essentials:reach").set("block_range", "8").set("entity_range", "8").enable();
		t.player().at(0.5, 0, 6.5, 180, 20);
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean four = t.waitUntil("4 bottles put away", () -> countIn(t, -2, 0, -1, "experience_bottle") >= 4, 800);
		t.module(TRADER).disable();
		t.module("myriad-essentials:reach").disable();
		int bottles = countIn(t, -2, 0, -1, "experience_bottle");
		t.server().run("kill @e[type=villager]");
		String detail = bottles + " bottles put away from 6 blocks off with Reach at 8";
		t.check(four && bottles == 4, detail);
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
		t.module(TRADER).set("inputs", in).set("outputs", out).set("profession", "minecraft:cleric").set("trade", "minecraft:experience_bottle");
		t.waitTicks(20);
		t.flagsFromHere();
		t.module(TRADER).enable();
		boolean done = t.waitUntil("Auto Trader to wait for more", () -> t.onGame(() -> ((AutoTrader) t.module(TRADER).get()).waiting()), 1000);
		t.module(TRADER).disable();
		int bottles = countIn(t, -2, 0, -1, "experience_bottle"), blocks = countIn(t, 2, 0, -1, "emerald_block");
		t.server().run("kill @e[type=villager]");
		String detail = String.format(Locale.ROOT, "%s; %d bottles in the output chest, %d emerald blocks left in the input", done ? "waiting for more" : "still going", bottles, blocks);
		t.check(done && bottles == 18 && blocks == 0, detail);
		t.note(detail);
	}
}
