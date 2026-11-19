package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.TestContext;
import dev.myriadclient.testing.TestSuite;

/**
 * What every Essentials suite starts from: Elytra Tweaks on with No Durability (for whoever flies), and
 * Elytra Fly's lane and steering as the tests expect them. Settings set here are put back after each test with the rest.
 */
public abstract class EssentialsSuite implements TestSuite {
	@Override
	public void beforeEach(TestContext t) {
		t.module("myriad-essentials:elytra_tweaks").set("no_durability", "true").enable();
		t.module("myriad-essentials:elytra_fly").set("line", "middle").set("steer_round", "true");
	}
}
