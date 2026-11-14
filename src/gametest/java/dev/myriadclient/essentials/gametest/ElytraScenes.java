package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.TestContext;

/** What Altitude's and No Durability's flights share: a start high in the open, and reading the elytra's wear. */
final class ElytraScenes {
	private ElytraScenes() {
	}

	/**
	 * Teleports the player to a relative position facing east (yaw -90), level, without waiting to stand there: it's
	 * high in the air and starts falling at once.
	 */
	static void launch(TestContext t, double x, double y, double z) {
		t.player().teleport(x, y, z, -90, 0);
	}

	/** The elytra's damage, worn (chest) or swapped off into the hotbar; -1 if it's gone. */
	static int elytraDamage(TestContext t) {
		for (int slot : new int[] {38, 0, 1, 2}) {
			if (t.player().item(slot).equals("minecraft:elytra")) return t.player().damage(slot);
		}
		return -1;
	}
}
