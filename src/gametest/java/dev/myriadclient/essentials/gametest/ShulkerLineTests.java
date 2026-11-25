package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Long runs (only with {@code -PlongTests} on the test client): Auto Craft fed by shulker unloaders into a shulker loader,
 * the boxes swapped by the test as the machines would (an emptied input box taken away and the next full one there a
 * second later; the output box mined into a double chest once full, as a loader's hopper would take it, and an empty one
 * there a second later).
 */
public final class ShulkerLineTests extends EssentialsSuite {
	private static final Logger LOG = LoggerFactory.getLogger("ShulkerLine");
	private static final String CRAFT = "myriad-essentials:auto_craft";
	/** Ticks from a box going to the next one being there. */
	private static final int SWAP_TICKS = 20;

	private static boolean longRun(TestContext t) {
		if (Boolean.getBoolean("myriad.longTests")) return true;
		t.skip("a long run: start the test client with -PlongTests");
		return false;
	}

	/** A shulker box station: where its box goes, the template it's cloned from, how many more there are. */
	private static final class Station {
		final String pos, template;
		int left, swapAt = -1, swapped;

		Station(String pos, String template, int left) {
			this.pos = pos;
			this.template = template;
			this.left = left;
		}
	}

	private static String at(TestContext t, int x, int y, int z) {
		var a = t.arena();
		return a.x(x) + " " + a.y(y) + " " + a.z(z);
	}

	private static int count(TestContext t, String pos, String item) {
		String out = t.server().run("execute if items block " + pos + " container.* " + item);
		int i = out.lastIndexOf("count: ");
		return i < 0 ? 0 : Integer.parseInt(out.substring(i + 7).trim());
	}

	/** An input: its box taken away once it's empty, the next full one cloned in {@link #SWAP_TICKS} later. */
	private static void unloader(TestContext t, Station s, int tick) {
		if (s.swapAt >= 0) {
			if (tick < s.swapAt) return;
			s.swapAt = -1;
			if (s.left <= 0) return;
			t.server().run("clone " + s.template + " " + s.template + " " + s.pos);
			s.left--;
			return;
		}
		if (t.server().passes("if block " + s.pos + " minecraft:shulker_box unless items block " + s.pos + " container.* *")) {
			t.server().run("setblock " + s.pos + " air");
			s.swapped++;
			s.swapAt = tick + SWAP_TICKS;
		}
	}

	@Test(name = "double_chest_of_ender_chests", tags = "long", timeoutTicks = 20 * 60 * 120)
	public void doubleChestOfEnderChests(TestContext t) {
		// 54 shulker boxes of ender chests (93,312: 746,496 obsidian in 432 boxes, 93,312 eyes in 54) into a double chest.
		if (!longRun(t)) return;
		t.server().run("recipe give " + t.player().name() + " *");
		t.server().run("kill @e[type=item]");
		String obsidianTemplate = at(t, 30, 0, 20), eyeTemplate = at(t, 30, 0, 17);
		t.server().run("setblock " + obsidianTemplate + " shulker_box");
		t.server().run("setblock " + eyeTemplate + " shulker_box");
		for (int i = 0; i < 27; i++) {
			t.server().run("item replace block " + obsidianTemplate + " container." + i + " with obsidian 64");
			t.server().run("item replace block " + eyeTemplate + " container." + i + " with ender_eye 64");
		}
		Station obsidian = new Station(at(t, 2, 0, 2), obsidianTemplate, 432), eyes = new Station(at(t, 2, 0, -2), eyeTemplate, 54);
		t.arena().set(2, 0, 0, "crafting_table");
		t.server().run("clone " + obsidianTemplate + " " + obsidianTemplate + " " + obsidian.pos);
		obsidian.left--;
		t.server().run("clone " + eyeTemplate + " " + eyeTemplate + " " + eyes.pos);
		eyes.left--;
		// The loader: its box at (-2, 0, 2), and the double chest the full ones go into under the floor.
		String out = at(t, -2, 0, 2), chestA = at(t, -2, -2, 2), chestB = at(t, -1, -2, 2);
		t.server().run("setblock " + chestA + " chest[facing=north,type=left]");
		t.server().run("setblock " + chestB + " chest[facing=north,type=right]");
		t.server().run("setblock " + out + " shulker_box");
		t.player().at(0.5, 0, 0.5, -90, 20);
		t.module(CRAFT).set("items", "clear").set("items", "add minecraft:ender_chest")
			.set("inputs", obsidian.pos + "; " + eyes.pos).set("outputs", out);
		t.waitTicks(10);
		t.flagsFromHere();
		long start = System.currentTimeMillis();
		int tick = 0, full = 0, outSwapAt = -1;
		t.module(CRAFT).enable();
		try {
			while (full < 54) {
				t.waitTicks(2);
				tick += 2;
				unloader(t, obsidian, tick);
				unloader(t, eyes, tick);
				if (outSwapAt >= 0) {
					if (tick >= outSwapAt) {
						outSwapAt = -1;
						if (full < 54) t.server().run("setblock " + out + " shulker_box");
					}
				} else if (count(t, out, "minecraft:ender_chest") >= 1728) {
					// Mined into the double chest, contents and all (a half at a time: an insert fills only the one it names).
					t.server().run("loot insert " + (full < 27 ? chestA : chestB) + " mine " + out);
					t.server().run("setblock " + out + " air");
					full++;
					outSwapAt = tick + SWAP_TICKS;
					double minutes = (System.currentTimeMillis() - start) / 60000.0;
					LOG.info(String.format(Locale.ROOT, "box %d/54 full at %.1f min (obsidian boxes used %d, eye boxes %d)", full, minutes,
						obsidian.swapped, eyes.swapped));
				}
				if (tick % 6000 == 0 && obsidian.left <= 0 && obsidian.swapAt < 0 && !t.server().passes("if block " + obsidian.pos + " minecraft:shulker_box")) break;
			}
			t.waitTicks(80);
		} finally {
			t.module(CRAFT).disable();
			t.module(CRAFT).set("inputs", "").set("outputs", "");
		}
		double minutes = (System.currentTimeMillis() - start) / 60000.0;
		int boxes = count(t, chestA, "minecraft:shulker_box") + count(t, chestB, "minecraft:shulker_box");
		String detail = String.format(Locale.ROOT, "%d/54 boxes of ender chests in the double chest in %.1f min (%d ender chests, %.0f a minute)", boxes, minutes,
			full * 1728, full * 1728 / minutes);
		for (String p : new String[] {chestA, chestB}) t.server().run("setblock " + p + " stone");
		t.server().run("setblock " + obsidianTemplate + " air");
		t.server().run("setblock " + eyeTemplate + " air");
		LOG.info(detail);
		t.check(boxes == 54, detail);
		t.note(detail);
	}
}
