package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.essentials.modules.automation.Printer;
import dev.myriadclient.essentials.util.BuildPlanRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a block under you as you walk, run or jump, built the way the Printer builds: a blueprint worked out each tick
 * (the cells under where you'll be over the next few ticks, at the height you're walking at) that the building service
 * places, nearest first, moving blocks to the hotbar as it goes.
 * <ul>
 *   <li>Moving diagonally, a cell beside the path goes in between, so every cell has the one before it to be placed
 *   against.</li>
 *   <li>Over nothing at all (you ran off a corner, or fell), it bridges from the nearest block it can reach.</li>
 *   <li>Jumping raises the height it builds at: holding jump in place builds a tower. Jumping while you run doesn't, so
 *   you land back on the line you were building.</li>
 *   <li>If the block where you're about to step hasn't gone out yet (waiting on the server's placement limit, or on a
 *   turn with Rotate), you're held at the edge by sneaking for that tick, so you don't walk off.</li>
 * </ul>
 * Without Rotate, blocks are clicked without turning, on any face, as 2b2t takes them, and you keep running at full
 * speed. With Rotate each placement faces its block first and only faces you can see are clicked, for Grim builds that
 * check (RotationPlace, PositionPlace); while facing one you walk along that yaw.
 */
public class Scaffold extends Module {
	private final RegistryListSetting<Block> blocks = sgGeneral.blocks("Blocks").description("Only use these. Empty uses the block you hold, or any "
		+ "ordinary building block.").build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Face each block before placing it, and click only faces you can "
		+ "see, for servers that check where you look. You walk along each turn for a moment; 2b2t doesn't need it.").build();
	private final BoolSetting airPlace = sgGeneral.bool("Air Place").description("Place under you with nothing next to it, through the off "
		+ "hand, so it never has to bridge. 2b2t takes it; current Grim refuses it.").build();

	private final SettingGroup sgRender = settings.group("Render");
	private final BoolSetting showPlan = sgRender.bool("Show Plan").description("Outline the blocks about to be placed and those waiting.").build();
	private final RenderSettings look = RenderSettings.builder(sgRender).visible(showPlan::get).build();

	/** Most placements a tick without turning (the server's own limit, 9 in 300 ms, holds back bursts); one with Rotate. */
	private static final int PER_TICK = 3;
	/** How far ahead it builds, in ticks of your current speed. */
	private static final double AHEAD_TICKS = 4;
	/** How far ahead, in ticks of your speed, a step is checked for a block before you're held at the edge. */
	private static final double EDGE_TICKS = 1.5;
	/** Step along your path when finding the cells you'll cross. */
	private static final double SAMPLE = 0.1;
	/** Most cells a bridge to a block you can reach takes. */
	private static final int BRIDGE_STEPS = 4;

	private static final Direction[] PLANE = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private @Nullable Build build;
	private Building.@Nullable Options running;
	/** The layer it builds in: the one under your feet when you last stood on something, raised as you jump. */
	private int level = Integer.MIN_VALUE;

	public Scaffold() {
		super(Tags.WORLD, "Scaffold", "Keeps a block under you as you walk.");
	}

	private Building.Options options() {
		boolean turn = rotate.get();
		// Clicks as if sneaking where the block below opens or toggles (a chest, a crafting table), so it bridges past them.
		Placement.Options placing = Placement.Options.forServer().withRotate(turn).withVisibleFaces(turn).withAirPlace(airPlace.get())
			.withRange(Reach.blockRange()).withSneak(true);
		// Never breaks anything: whatever you can stand on already does.
		return Building.Options.forServer().withPlacing(placing).withBreakWrong(false).withKeepUp(true).withOrder(Building.Order.NEAREST)
			.withPlacesPerTick(turn ? 1 : PER_TICK);
	}

	@Override
	protected void onEnable() {
		level = Integer.MIN_VALUE;
		start();
	}

	@Override
	protected void onDisable() {
		Build b = build;
		build = null;
		running = null;
		if (b != null) b.stop();
	}

	private void start() {
		running = options();
		build = Myriad.building().start(this, Blueprint.dynamic(this::plan), running);
	}

	/** Before the building service plans this tick: the layer follows you, and a changed setting starts the build over. */
	@Subscribe(priority = Priority.HIGHEST)
	private void onTick(TickEvent.Pre e) {
		if (!inGame()) return;
		if (build == null || !build.isRunning() || !options().equals(running)) {
			if (build != null) build.stop();
			start();
		}
		updateLevel();
	}

	private void updateLevel() {
		double feet = mc.player.getY();
		if (mc.player.onGround()) {
			// The cell you stand on: the full block under your feet, or the slab or path block you're in.
			level = Mth.floor(feet - 1e-3);
		} else if (level == Integer.MIN_VALUE || feet < level + 1 - 1e-3) {
			// Fell below the layer (or never stood on one): build where you are, to catch you.
			level = Mth.floor(feet) - 1;
		} else if (mc.player.input.keyPresses.jump() && !moving()) {
			// Towering: once your feet are clear of the next cell up, that's the layer.
			level = Math.max(level, Mth.floor(feet) - 1);
		}
	}

	private boolean active() {
		return inGame() && level != Integer.MIN_VALUE && !mc.player.getAbilities().flying && !mc.player.isFallFlying() && !mc.player.isPassenger()
			&& !mc.player.isSpectator();
	}

	private boolean moving() {
		return step().horizontalDistanceSqr() > 1e-4;
	}

	/** How far you moved across the last tick (the motion left over after it is less: ground friction takes a part). */
	private Vec3 step() {
		return new Vec3(mc.player.getX() - mc.player.xo, 0, mc.player.getZ() - mc.player.zo);
	}

	// ---- the blueprint --------------------------------------------------------------------------------------------

	/** The cells to fill this tick, nearest first: under you, along where you're going, and a bridge to them if needed. */
	private Map<BlockPos, Target> plan() {
		Map<BlockPos, Target> plan = new LinkedHashMap<>();
		if (!active() || !hasBlocks()) return plan;
		Target target = target();
		List<BlockPos> path = path();
		boolean air = airPlace.get();
		for (int i = 0; i < path.size(); i++) {
			BlockPos cell = path.get(i);
			// The first cell with nothing to go against: a way to it from a block in reach goes first.
			if (!air && plan.isEmpty() && open(cell) && !supported(cell, plan)) {
				for (BlockPos p : bridge(cell)) plan.put(p, target);
			}
			plan.put(cell, target);
		}
		return plan;
	}

	/**
	 * The cells in the layer under you and under where you'll be in the next few ticks, each one beside the last (a
	 * diagonal step gets the cell your path crosses first in between).
	 */
	private List<BlockPos> path() {
		List<BlockPos> cells = new ArrayList<>(8);
		Vec3 pos = mc.player.position(), motion = step();
		double speed = motion.horizontalDistance();
		double ahead = speed * AHEAD_TICKS;
		double dx = speed > 1e-6 ? motion.x / speed : 0, dz = speed > 1e-6 ? motion.z / speed : 0;
		BlockPos last = null;
		for (double s = 0; ; s += SAMPLE) {
			double x = pos.x + dx * Math.min(s, ahead), z = pos.z + dz * Math.min(s, ahead);
			BlockPos cell = new BlockPos(Mth.floor(x), level, Mth.floor(z));
			if (last == null) {
				cells.add(cell);
			} else if (!cell.equals(last)) {
				if (cell.getX() != last.getX() && cell.getZ() != last.getZ()) {
					// Through a corner: the side cell nearer the line goes in between.
					BlockPos a = new BlockPos(cell.getX(), level, last.getZ()), b = new BlockPos(last.getX(), level, cell.getZ());
					double da = Vec3.atCenterOf(a).subtract(x, 0, z).horizontalDistanceSqr(), db = Vec3.atCenterOf(b).subtract(x, 0, z).horizontalDistanceSqr();
					cells.add(da <= db ? a : b);
				}
				cells.add(cell);
			}
			last = cell;
			if (s >= ahead) break;
		}
		return cells;
	}

	/**
	 * The shortest way, at most {@link #BRIDGE_STEPS} cells, from a block in reach to {@code cell}, in the layer or one
	 * under it: the cells to place first, the one against an existing block first. Empty if there's none.
	 */
	private List<BlockPos> bridge(BlockPos cell) {
		Map<BlockPos, BlockPos> from = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		from.put(cell, cell);
		queue.add(cell);
		double range = Reach.blockRange();
		while (!queue.isEmpty()) {
			BlockPos at = queue.poll();
			if (at != cell && supported(at, Map.of())) {
				// Back along the way it was found: from the supported cell towards the one wanted.
				List<BlockPos> way = new ArrayList<>();
				for (BlockPos p = at; !p.equals(cell); p = from.get(p)) way.add(p);
				return way;
			}
			if (steps(at, from) >= BRIDGE_STEPS) continue;
			List<BlockPos> next = new ArrayList<>(5);
			for (Direction d : PLANE) next.add(at.relative(d));
			if (at.getY() == level) next.add(at.below());
			for (BlockPos n : next) {
				if (from.containsKey(n) || !open(n) || !Reach.canReach(n, range)) continue;
				from.put(n, at);
				queue.add(n);
			}
		}
		return List.of();
	}

	private static int steps(BlockPos at, Map<BlockPos, BlockPos> from) {
		int n = 0;
		for (BlockPos p = at; !from.get(p).equals(p); p = from.get(p)) n++;
		return n;
	}

	/** Whether a block can go in {@code pos} (nothing there you could stand on). */
	private boolean open(BlockPos pos) {
		return mc.level.getBlockState(pos).canBeReplaced();
	}

	/** Whether {@code pos} has a block, or a cell planned before it, to be placed against. */
	private boolean supported(BlockPos pos, Map<BlockPos, Target> planned) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (planned.containsKey(n) || !mc.level.getBlockState(n).canBeReplaced()) return true;
		}
		return false;
	}

	/** Anything you can stand on counts as done; filled with the block you hold if it'll do. */
	private Target target() {
		Set<Block> allowed = blocks.get();
		ItemStack held = mc.player.getMainHandItem();
		return new Target() {
			@Override
			public boolean matches(BlockGetter level, BlockPos pos, BlockState state) {
				return !state.canBeReplaced() && !state.getCollisionShape(level, pos).isEmpty();
			}

			@Override
			public int preference(ItemStack stack) {
				if (!(stack.getItem() instanceof BlockItem item)) return -1;
				boolean ok = allowed.isEmpty() ? Target.solid().preference(stack) >= 0 : allowed.contains(item.getBlock());
				if (!ok) return -1;
				// What you're holding first, as long as it's allowed.
				return ItemStack.isSameItemSameComponents(stack, held) ? 0 : 1;
			}

			@Override
			public String describe() {
				return "a block to stand on";
			}
		};
	}

	private boolean hasBlocks() {
		Target t = target();
		return Myriad.inventory().findInHotbar(s -> t.preference(s) >= 0) >= 0 || Myriad.inventory().findInInventory(s -> t.preference(s) >= 0) >= 0;
	}

	// ---- the edge -------------------------------------------------------------------------------------------------

	/**
	 * Holds you at the edge (sneaking, for this tick) when the cell you're about to step over has no block yet: it's
	 * waiting on the server's placement limit or a turn. Not while you jump, nor with no blocks to place.
	 */
	@Subscribe
	private void onInput(InputEvent e) {
		if (!active() || e.jump || e.sneak || !e.isMoving() || !mc.player.onGround() || !moving() || !hasBlocks()) return;
		Vec3 pos = mc.player.position(), next = pos.add(step().scale(EDGE_TICKS));
		BlockPos cell = new BlockPos(Mth.floor(next.x), level, Mth.floor(next.z));
		// Only where you'd drop: not on a slab's layer, nor a step down onto a block.
		if (level == Mth.floor(pos.y - 1e-3) && open(cell) && open(cell.below())) e.sneak = true;
	}

	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		if (showPlan.get()) BuildPlanRenderer.draw(e, build, look);
	}
}
