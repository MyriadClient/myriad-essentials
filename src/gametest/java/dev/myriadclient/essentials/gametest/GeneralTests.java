package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The client as a whole: what it makes of the server's anti-cheat, and every module switched on and off in turn. */
public final class GeneralTests extends EssentialsSuite {
	private static final Pattern REPORT = Pattern.compile("selftest: (\\d+) modules run, (\\d+) failures");
	private static final Pattern HELPERS = Pattern.compile("(\\d+) holes, (\\d+) crystal bases, snowball lands \\(([-\\d.E]+), ([-\\d.E]+), ([-\\d.E]+)\\)");

	@Test(tags = "general")
	public void anticheat(TestContext t) {
		t.step(".anticheat");
		t.waitTicks(2);
		String status = t.chat().stream().filter(l -> l.contains("Anti-cheat:")).reduce((a, b) -> b).orElse("no answer in chat");
		t.check(status.contains("Grim detected"), status);
		t.note(status);
	}

	/** Every module but Auto Disconnect on for 20 ticks and off again; Grim's opinion of that isn't part of the result. */
	@Test(tags = "general", timeoutTicks = 12000, anyFlags = true)
	public void selftest(TestContext t) {
		// Looking down at the floor, with a bedrock hole and an obsidian crystal base nearby for the helpers to find.
		t.player().at(0.5, 0, 0.5, -90, 30);
		t.player().give(0, "diamond_sword").give(9, "totem_of_undying").give(8, "cooked_beef", 16);
		t.arena().set(5, -1, 4, "bedrock").fill(4, 0, 4, 6, 0, 4, "bedrock").set(5, 0, 3, "bedrock").set(5, 0, 5, "bedrock").set(5, 0, 4, "air")
			.set(2, 0, -3, "obsidian");
		t.waitTicks(20);
		List<String> said = t.step("selftest 20 -auto_disconnect");
		Matcher m = null;
		List<String> fails = new ArrayList<>();
		String helpers = "";
		for (String line : said) {
			Matcher r = REPORT.matcher(line);
			if (r.find()) m = r;
			if (line.contains("selftest: FAIL ")) fails.add(line.substring(line.indexOf("FAIL ") + 5));
			if (line.contains("selftest: helpers: ") && helpers.isEmpty()) helpers = line.substring(line.indexOf("helpers: ") + 9);
		}
		if (m == null) {
			t.check(false, "no selftest report");
			return;
		}
		// A snowball thrown where you look lands on the floor's top (y 0 in the arena).
		Matcher h = HELPERS.matcher(helpers);
		boolean helpersOk = h.find() && Integer.parseInt(h.group(1)) >= 1 && Integer.parseInt(h.group(2)) >= 1
			&& Math.abs(Double.parseDouble(h.group(4)) - t.arena().y(0)) < 0.01;
		String detail = m.group(1) + " modules, " + m.group(2) + " failures"
			+ (fails.isEmpty() ? "" : "; " + String.join("; ", fails.subList(0, Math.min(3, fails.size()))))
			+ (helpers.isEmpty() ? "" : " | helpers" + (helpersOk ? "" : " WRONG") + ": " + helpers);
		t.check(Integer.parseInt(m.group(2)) == 0 && helpersOk, detail);
		t.note(detail);
	}
}
