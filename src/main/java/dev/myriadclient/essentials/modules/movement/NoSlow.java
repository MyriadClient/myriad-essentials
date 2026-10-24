package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Moves at speed while you eat, drink, block or draw a bow, and through cobwebs and sweet berry bushes.
 * <p>
 * Items: where the server simulates movement (Grim), one tick in three at full speed. Grim tries each tick both with
 * the item's slowdown and without, so movement itself passes, and its NoSlow check flags only two ticks in a row it
 * can't clear while it thinks you use an item ({@link #CYCLE} says why that takes two slowed ticks after a fast one):
 * about twice the walking speed the item allows. Elsewhere, every tick.
 * <p>
 * Webs: only where the server doesn't simulate movement. Grim forces a web's slowdown on its prediction, and reporting
 * the webs broken (so its world holds air there for a moment) draws its breaking checks (WrongBreak, PositionBreak)
 * as well as Simulation, on Grim 2.3.74 at least.
 */
public class NoSlow extends Module {
	private final BoolSetting items = sgGeneral.bool("Items").description("Eat, drink, block and draw a bow without slowing to a crawl.").defaultValue(true).build();
	private final BoolSetting webs = sgGeneral.bool("Webs").description("Walk through cobwebs and sweet berry bushes at full speed, where the server doesn't simulate movement (not Grim).")
		.defaultValue(true).build();

	/**
	 * Where the server simulates movement, one tick in this many goes at full speed: Grim's predictions for a tick carry
	 * the previous tick's "not using" guess, so the tick after a fast one gives its NoSlow check nothing to clear you
	 * with, and only a second slowed tick does. One in two flags; one in three never has.
	 */
	private static final int CYCLE = 3;
	/** Ticks before an item's use ends that go slowed where the server simulates movement. */
	private static final int FINISH_TICKS = 3;

	/** Ticks spent using an item so far, and whether this one goes at full speed. */
	private int useTicks;
	private boolean fullSpeed;

	public NoSlow() {
		super(Tags.MOVEMENT, "No Slow", "Move at speed while eating or using items, and through cobwebs.");
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (mc.player.isUsingItem()) {
			// Slowed for the last ticks of a use: once it's done you walk at full speed while Grim still has you using it
			// until the server says otherwise, which mustn't follow a fast tick.
			boolean ending = mc.player.getUseItemRemainingTicks() <= FINISH_TICKS;
			fullSpeed = !Myriad.antiCheat().isStrict() || !ending && useTicks++ % CYCLE == 1;
		} else {
			// A use starts during a tick, after this: its first tick goes slowed, as Grim has it from the first packet.
			useTicks = 0;
			fullSpeed = false;
		}
	}

	private static boolean slows(BlockState state) {
		return state.is(Blocks.COBWEB) || state.is(Blocks.SWEET_BERRY_BUSH);
	}

	/** The item-use speed for this tick: full while No Slow is on and this tick goes at speed, {@code slowed} otherwise. */
	public static float itemSpeed(float slowed) {
		NoSlow noSlow = Modules.active(NoSlow.class);
		return noSlow != null && noSlow.items.get() && noSlow.fullSpeed ? 1 : slowed;
	}

	/** Whether {@code state} leaves you free to move: a web or berry bush with Webs on, where the server doesn't simulate movement. */
	public static boolean ignores(BlockState state) {
		NoSlow noSlow = Modules.active(NoSlow.class);
		return noSlow != null && noSlow.webs.get() && slows(state) && !Myriad.antiCheat().isStrict();
	}
}
