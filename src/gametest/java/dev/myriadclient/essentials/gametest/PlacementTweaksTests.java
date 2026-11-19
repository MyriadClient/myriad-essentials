package dev.myriadclient.essentials.gametest;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Placement Tweaks' Fast Place: right-click held with a block, looking at the sky (nothing to place against, so each use
 * goes out as a plain use and the count isn't held up by blocks in the way), for two seconds.
 */
public final class PlacementTweaksTests extends EssentialsSuite {
	private static final String TWEAKS = "myriad-essentials:placement_tweaks";

	/** Uses sent in 40 ticks of right-click held with stone, Fast Place at {@code speed}% (-1 for off). */
	private static int usesIn40Ticks(TestContext t, int speed) {
		t.player().give(0, "stone", 64).select(0);
		t.player().at(0.5, 0, 0.5, -90, -90);
		t.module(TWEAKS).set("fast_place", String.valueOf(speed >= 0)).set("speed", String.valueOf(Math.max(speed, 0))).enable();
		t.waitTicks(5);
		AtomicInteger uses = new AtomicInteger();
		var sub = Myriad.events().listen(PacketEvent.Send.class, e -> {
			if (e.packet() instanceof ServerboundUseItemPacket) uses.incrementAndGet();
		});
		try {
			t.step("mouse 1 press");
			t.waitTicks(40);
			t.step("mouse 1 release");
		} finally {
			sub.unsubscribe();
		}
		t.module(TWEAKS).set("fast_place", "false");
		return uses.get();
	}

	@Test(name = "fast_place", tags = "world")
	public void fastPlace(TestContext t) {
		int vanilla = usesIn40Ticks(t, -1), twice = usesIn40Ticks(t, 100), every = usesIn40Ticks(t, 300);
		String detail = "uses in 2 s: " + vanilla + " vanilla, " + twice + " at 100%, " + every + " at 300%";
		// Vanilla every 4 ticks (about 10), twice as fast at 100% (about 20), every tick at 300% (about 40).
		t.check(vanilla <= 12 && twice >= 18 && twice <= 22 && every >= 36, detail);
		t.note(detail);
	}
}
