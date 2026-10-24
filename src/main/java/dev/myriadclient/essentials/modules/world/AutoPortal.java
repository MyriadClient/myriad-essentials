package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InteractEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.PlacementSettings;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.essentials.util.BuildPlanRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Right-click the top of a block with obsidian and a Nether portal is built standing on it, across your view, then lit.
 * With Trigger on Enable, turning the module on builds one where you look (or on the ground two blocks ahead) and turns
 * the module off again once it's lit, for a tap bind.
 * The frame is the smallest that lights (2 wide, 3 tall inside); anything in the way inside is broken first.
 */
public class AutoPortal extends Module {
	private final EnumSetting<Trigger> trigger = sgGeneral.oneOf("Trigger", Trigger.CLICK).description("Click: right-click the top of a "
		+ "block with obsidian. Enable: turning the module on builds one where you look (or on the ground in front of you) and turns it off "
		+ "again, for a tap bind.").build();
	private final BoolSetting corners = sgGeneral.bool("Corners").description("Place the frame's four corners too. Without air placement "
		+ "the sides have nothing else to be placed against.").defaultValue(true).build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Look at each block's face to place it, and only click faces you "
		+ "can see, as servers that check where you look want (current Grim). Off (2b2t), any face in reach is clicked without turning: "
		+ "much faster.").defaultValue(true).build();
	private final PlacementSettings placing = new PlacementSettings(sgGeneral);
	private final BoolSetting light = sgGeneral.bool("Light").description("Light the portal with flint and steel (or a fire charge) once the "
		+ "frame is up.").defaultValue(true).build();

	private final SettingGroup sgRender = settings.group("Render");
	private final BoolSetting showPlan = sgRender.bool("Show Plan").description("Outline the frame while it's built.").defaultValue(true).build();
	private final RenderSettings look = RenderSettings.builder(sgRender).visible(showPlan::get).build();

	/** Most blocks placed a tick. */
	private static final int PER_TICK = 3;
	/** How long a portal from Enable may take before giving up, in ticks. */
	private static final int ONE_SHOT_TICKS = 400;
	/** How far ahead of you Enable builds when you aren't looking at a block. */
	private static final int AHEAD = 2;
	/** Tries at lighting before giving up (a click the server didn't take, a mob in the way). */
	private static final int LIGHT_TRIES = 3;
	/** Ticks to wait for the portal after each try. */
	private static final int LIGHT_WAIT = 20;

	private final Predicate<ItemStack> igniter = s -> s.is(Items.FLINT_AND_STEEL) || s.is(Items.FIRE_CHARGE);
	private @org.jetbrains.annotations.Nullable Build build;
	/** The frame block to light on top of (the bottom row's inside), while lighting; null otherwise. */
	private BlockPos lightOn;
	private int lightTries, lightWait;
	/** Building one portal for Enable, then turning off; and the tick it started. */
	private boolean oneShot;
	private int oneShotTicks;

	public enum Trigger {
		CLICK, ENABLE
	}

	/** 2: the plan's box options became one look for both render modes (the Render window). */
	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) RenderSettings.migrate(saved, "Render", null);
	}

	public AutoPortal() {
		super(Tags.WORLD, "Auto Portal", "Builds a Nether portal where you right-click with obsidian, or in front of you when turned on.");
		formerNames("Portal Builder");
	}

	@Override
	protected void onEnable() {
		oneShot = false;
		if (trigger.get() != Trigger.ENABLE) return;
		if (!inGame()) {
			disable();
			return;
		}
		int needed = corners.get() ? 14 : 10;
		if (Myriad.inventory().count(s -> s.is(Items.OBSIDIAN)) < needed) {
			warn("Needs " + needed + " obsidian.");
			disable();
			return;
		}
		Direction facing = mc.player.getDirection();
		BlockPos base = spot(facing);
		if (base == null) {
			warn("No ground in front of you to build on.");
			disable();
			return;
		}
		oneShot = true;
		oneShotTicks = 0;
		build(base, facing);
	}

	/**
	 * Where Enable builds: on top of the block you're looking at, else on the ground {@link #AHEAD} blocks in front of
	 * you (the first block below that spot, a few down at most). Null if there's no ground there.
	 */
	private BlockPos spot(Direction facing) {
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
			return hit.getBlockPos().above();
		}
		BlockPos ahead = mc.player.blockPosition().relative(facing, AHEAD);
		for (int dy = 1; dy >= -4; dy--) {
			BlockPos p = ahead.above(dy);
			if (!mc.level.getBlockState(p.below()).canBeReplaced() && mc.level.getBlockState(p).canBeReplaced()) return p;
		}
		return null;
	}

	/** One portal from Enable is over (built and lit, or given up): the module turns off. */
	private void oneShotDone() {
		if (oneShot && isEnabled()) disable();
	}

	@Override
	protected void onDisable() {
		Build b = build;
		build = null;
		if (b != null) b.stop();
		Myriad.inventory().giveBack(this);
		lightOn = null;
		oneShot = false;
	}

	@Subscribe
	private void onInteract(InteractEvent.Block e) {
		// Only your own click, while nothing's being built (the build's placements post this event too).
		if (trigger.get() != Trigger.CLICK || e.hand() != InteractionHand.MAIN_HAND || build != null && build.isRunning() || lightOn != null) return;
		if (!mc.options.keyUse.isDown() || !mc.player.getMainHandItem().is(Items.OBSIDIAN)) return;
		BlockHitResult hit = e.hit();
		if (hit.getDirection() != Direction.UP) return;
		e.cancel();
		build(hit.getBlockPos().above(), mc.player.getDirection());
	}

	/** Builds a portal whose bottom frame row's inside starts at {@code base}, facing {@code facing}. */
	public void build(BlockPos base, Direction facing) {
		Direction along = facing.getClockWise();
		Map<BlockPos, Target> frame = new HashMap<>();
		Target obsidian = Target.exact(Blocks.OBSIDIAN.defaultBlockState());
		for (int i = -1; i <= 2; i++) {
			for (int y = 0; y <= 4; y++) {
				BlockPos p = base.relative(along, i).above(y);
				boolean side = i == -1 || i == 2, edge = y == 0 || y == 4;
				if (side && edge) {
					if (corners.get()) frame.put(p, obsidian);
				} else if (side || edge) {
					frame.put(p, obsidian);
				} else {
					frame.put(p, Target.air());
				}
			}
		}
		// As many a tick as the packet budget allows (it holds the rest back): a frame is 10 to 14 blocks.
		Building.Options o = Building.Options.forServer().withBreakWrong(true).withPlacesPerTick(PER_TICK)
			.withBreaking(Breaking.Options.forServer().withRange(placing.reach()))
			.withPlacing(placing.options().withRotate(rotate.get()).withVisibleFaces(rotate.get()));
		build = Myriad.building().start(this, Blueprint.of(frame), o);
		build.finished().thenAccept(done -> {
			if (!isEnabled()) return;
			if (!done || !light.get()) {
				oneShotDone();
				return;
			}
			// Fire on the bottom of the frame, inside.
			lightOn = base;
			lightTries = 0;
			lightWait = 0;
		});
	}

	/** The frame being built, or null. */
	public @org.jetbrains.annotations.Nullable Build build() {
		return build;
	}

	@Subscribe(priority = Priority.HIGH)
	private void onTick(TickEvent.Pre e) {
		if (oneShot && ++oneShotTicks > ONE_SHOT_TICKS) {
			warn("Couldn't finish the portal here.");
			oneShotDone();
			return;
		}
		if (!inGame() || lightOn == null) return;
		if (mc.level.getBlockState(lightOn.above()).is(Blocks.NETHER_PORTAL)) {
			lightOn = null;
			oneShotDone();
			return;
		}
		if (lightWait > 0) {
			lightWait--;
			return;
		}
		if (lightTries >= LIGHT_TRIES) {
			warn("The portal didn't light.");
			lightOn = null;
			oneShotDone();
			return;
		}
		int slot = Myriad.inventory().findInHotbar(igniter);
		if (slot < 0) {
			int from = Myriad.inventory().findInInventory(igniter);
			if (from < 0) {
				warn("No flint and steel or fire charge to light the portal with.");
				lightOn = null;
				oneShotDone();
			} else {
				// Into any hotbar slot but the one you hold, put back once the portal's lit.
				Myriad.inventory().borrow(this, from, s -> true);
			}
			return;
		}
		// Fire on the top of the frame's bottom row, inside: turned onto it first where looks are checked.
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(lightOn).add(0, 0.5, 0), Direction.UP, lightOn, false);
		if (Myriad.placement().use(this, hit, slot, Placement.Options.forServer().withRotate(rotate.get()).withRange(placing.reach())).sent()) {
			lightTries++;
			lightWait = LIGHT_WAIT;
		}
	}

	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		if (showPlan.get()) BuildPlanRenderer.draw(e, build, look);
	}
}
