package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.MaterialSource;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Steps;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.BuildingSettings;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.Baritone;
import dev.myriadclient.api.util.TickCached;
import dev.myriadclient.essentials.litematica.LitematicaBlueprint;
import dev.myriadclient.essentials.util.BuildPlanRenderer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Builds the schematics placed in Litematica around you as you move: the layers Litematica shows, nearest and lowest
 * first. Every block comes out the way the schematic has it, facing, half, axis, hinge and all, from a click worked
 * out by simulating the placement, with the look turned to it first where the server checks. Repeaters,
 * comparators, note blocks, doors, trapdoors, gates and levers are then clicked into the schematic's setting; candles,
 * sea pickles, snow layers and double slabs get as many as it has. Blocks are moved to the hotbar as they're needed.
 * It runs as a job ({@code Steps.keepUp}), so the Jobs window and HUD show what it's doing and what it's waiting for.
 * <p>
 * Optionally it walks (Baritone) to the nearest unbuilt part once nothing in reach is left, and fetches a block that ran
 * out from the shulker boxes you carry and the chests, barrels and shulker boxes around you: a remedy on the job
 * ({@code whenStuck}), run when everything it could do is waiting on that block, after which it carries on.
 */
public class Printer extends Module {
	private final BuildingSettings building = new BuildingSettings(sgGeneral);
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Look at each block's face to place it, and only click faces you "
		+ "can see, as servers that check where you look want (current Grim). Off (2b2t), any face in reach is clicked, and the look only "
		+ "turns for blocks that face a way.").defaultValue(true).build();
	private final BoolSetting clicks = sgGeneral.bool("Click Settings").description("After placing, click repeaters, comparators, note blocks, "
		+ "doors, trapdoors, gates and levers into the schematic's setting.").defaultValue(true).build();
	private final EnumSetting<Building.Order> order = sgGeneral.oneOf("Order", Building.Order.BOTTOM_UP).description("Bottom Up: lowest first, "
		+ "a layer at a time, each block on the one under it. Nearest: what's closest first, whatever the height, so what's beside you is "
		+ "built as you pass it.").build();
	private final RegistryListSetting<Block> skip = sgGeneral.blocks("Skip").description("Blocks of the schematic to leave out: they're "
		+ "neither placed nor cleared for.").build();
	private final BoolSetting walk = sgGeneral.bool("Walk To Blocks").description("Once nothing in reach is left to build, walk to the nearest "
		+ "part that isn't built yet with Baritone (not mining or placing on the way). Needs Baritone.").build();

	private final SettingGroup sgRestock = settings.group("Restock");
	private final BoolSetting restock = sgRestock.bool("Restock").description("When a block runs out, fetch a stack of it and carry on.").build();
	private final BoolSetting fromShulkers = sgRestock.bool("From Shulker Boxes").description("From the shulker boxes in your inventory.")
		.defaultValue(true).visible(restock::get).build();
	private final BoolSetting fromContainers = sgRestock.bool("From Containers").description("From chests, barrels and shulker boxes "
		+ "around you: those in reach, or with Walk To Blocks, those within Container Range.").defaultValue(true).visible(restock::get).build();
	private final IntSetting containerRange = sgRestock.integer("Container Range").description("How far away a container is walked to, with "
		+ "Walk To Blocks.").defaultValue(16).range(4, 48).visible(() -> restock.get() && fromContainers.get() && walk.get()).build();

	private final SettingGroup sgRender = settings.group("Render");
	private final BoolSetting showPlan = sgRender.bool("Show Plan").description("Outline what's about to be placed, what's waiting, and what's stuck.")
		.defaultValue(true).build();
	private final RenderSettings look = RenderSettings.builder(sgRender).visible(showPlan::get).build();

	/** Ticks the job waits on a reason before a remedy runs (and between runs while it's still stuck on it). */
	private static final int REMEDY_TICKS = 20;
	/** How near a container the walk goes, to open it. */
	private static final int WALK_NEAR = 3;
	/** Ticks nothing in reach is left before walking on. */
	private static final int IDLE_TICKS = 10;
	/** How long a container that had none of a block isn't opened for it again, in ticks. */
	private static final int EMPTY_FOR_TICKS = 1200;

	private @Nullable Job<Void> job;
	private Building.Options running;
	private boolean runningWalk, runningRestock;
	/** Walking to the next unbuilt part (Walk To Blocks), beside the printing job; and how long nothing in reach was left. */
	private @Nullable Job<Void> walking;
	private int idleTicks;
	/** Spots already walked to for a block (it still couldn't be built from them), and blocks passed over for having none left. */
	private final Map<Long, LongOpenHashSet> triedSpots = new HashMap<>();
	private final LongOpenHashSet passedOver = new LongOpenHashSet();
	/** Containers that had none of a block when opened for it: by position, the blocks (as described) and until when. */
	private final Map<Long, Map<String, Long>> emptyFor = new HashMap<>();
	private boolean warnedNoBaritone;
	/** The build behind the job, found once a tick: the plan drawn every frame and the count in the module list read it. */
	private final TickCached<@Nullable Build> build = TickCached.of(this::findBuild);

	/** 2: the plan's box options became one look for both render modes (the Render window). */
	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) RenderSettings.migrate(saved, "Render", null);
	}

	public Printer() {
		super(Tags.AUTOMATION, "Printer", "Builds Litematica schematics around you.");
	}

	private Building.Options options() {
		Building.Options o = building.options();
		// Faces you can see matter only where the look has to be on them.
		return o.withKeepUp(true).withPlacing(o.placing().withRotate(rotate.get()).withVisibleFaces(rotate.get())).withClickSettings(clicks.get())
			.withOrder(order.get());
	}

	@Override
	protected void onEnable() {
		if (!FabricLoader.getInstance().isModLoaded("litematica")) {
			error("Litematica isn't installed.");
			disable();
			return;
		}
		start();
	}

	/** Builds the schematic as Litematica shows it (read live), with the settings as they are now. */
	private void start() {
		running = options();
		runningWalk = walk.get();
		runningRestock = restock.get();
		Step<Void> print = Steps.keepUp(blueprint(), running);
		if (runningRestock) print = print.whenStuck(Why.NO_ITEM, REMEDY_TICKS, why -> restock());
		Job<Void> mine = job = Myriad.jobs().start(this, print.named("Printing"));
		// A job that ended on its own (the build was stopped, or you left the world) turns the module off with it.
		mine.result().whenComplete((v, t) -> {
			if (isEnabled() && job == mine) disable();
		});
	}

	/** A changed setting takes effect at once: the build starts over with it. */
	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (job != null && (!options().equals(running) || walk.get() != runningWalk || restock.get() != runningRestock)) {
			Job<Void> old = job;
			job = null;
			old.cancel();
			start();
		}
		walkWhenIdle();
	}

	/**
	 * With Walk To Blocks, once nothing in reach has been left to build for half a second, walks on to the nearest unbuilt part
	 * (the build counts as waiting then, not stuck: it doesn't know how much is left beyond reach).
	 */
	private void walkWhenIdle() {
		Build b = job != null && runningWalk ? build() : null;
		boolean idle = b != null && b.steps().stream().allMatch(st -> st.status() == Build.Status.DONE || fixedByMoving(st.status()));
		idleTicks = idle ? idleTicks + 1 : 0;
		if (idleTicks < IDLE_TICKS || walking != null && !walking.result().isDone()) return;
		idleTicks = 0;
		walking = Myriad.jobs().start(this, walkOn().named("Walking on"));
	}

	@Override
	protected void onDisable() {
		Job<Void> j = job;
		job = null;
		if (j != null) j.cancel();
		Job<Void> w = walking;
		walking = null;
		if (w != null) w.cancel();
		idleTicks = 0;
		triedSpots.clear();
		passedOver.clear();
		emptyFor.clear();
	}

	/** The schematic as Litematica shows it, without the blocks in Skip. */
	private Blueprint blueprint() {
		return new LitematicaBlueprint(building.breakWrong::get, state -> skip.contains(state.getBlock()));
	}

	// ---- walking on ---------------------------------------------------------------------------------------------

	/** What standing somewhere else may make possible. */
	private static boolean fixedByMoving(Build.Status status) {
		return switch (status) {
			case OUT_OF_RANGE, NOT_VISIBLE, WRONG_ANGLE, SUPPORTS_YOU, ENTITY_IN_WAY -> true;
			default -> false;
		};
	}

	/** Walks to beside the nearest block of the schematic that isn't built yet and can't be from here; nothing if there's none. */
	private Step<Void> walkOn() {
		return Step.choose("Walk on", () -> {
			if (!Baritone.isAvailable()) {
				if (!warnedNoBaritone) warn("Walk To Blocks needs Baritone.");
				warnedNoBaritone = true;
				return Step.nothing();
			}
			// A block with no spot left to try is passed over for the next.
			for (int tries = 0; tries < 8; tries++) {
				BlockPos next = nearestUnbuilt();
				if (next == null) return Step.nothing();
				LongOpenHashSet tried = triedSpots.computeIfAbsent(next.asLong(), k -> new LongOpenHashSet());
				// Where you stand now didn't do for it.
				tried.add(mc.player.blockPosition().asLong());
				BlockPos spot = standingSpot(next, tried);
				if (spot == null) {
					passedOver.add(next.asLong());
					continue;
				}
				tried.add(spot.asLong());
				return walkingOnly(Steps.walkTo(spot));
			}
			return Step.nothing();
		});
	}

	/**
	 * The nearest position the schematic wants something other than what's there, that the build can't do from where you
	 * stand (out of reach, or waiting on a better place to stand), looked for close by first. Null if there's none in the
	 * schematic chunks Litematica has loaded.
	 */
	private @Nullable BlockPos nearestUnbuilt() {
		Build b = build();
		LongOpenHashSet doable = new LongOpenHashSet();
		if (b != null) for (Build.Step st : b.steps()) if (!fixedByMoving(st.status())) doable.add(st.pos().asLong());
		Blueprint blueprint = blueprint();
		Vec3 eyes = mc.player.getEyePosition();
		for (double range : new double[]{24, 64}) {
			BlockPos[] best = {null};
			double[] bestDistance = {Double.MAX_VALUE};
			blueprint.near(eyes, range, (pos, target) -> {
				double d = eyes.distanceToSqr(Vec3.atCenterOf(pos));
				if (d >= bestDistance[0] || doable.contains(pos.asLong()) || passedOver.contains(pos.asLong()) || !mc.level.isLoaded(pos)) return;
				if (target.matches(mc.level, pos, mc.level.getBlockState(pos))) return;
				best[0] = pos.immutable();
				bestDistance[0] = d;
			});
			if (best[0] != null) return best[0];
		}
		return null;
	}

	/**
	 * Somewhere to stand beside {@code pos} to build it: 2 to 3 blocks off, with ground under it and room for you, on no
	 * position the schematic still wants a block at (you'd be in the way of it), not one of {@code tried}, nearest you
	 * first. Null if there's none.
	 */
	private @Nullable BlockPos standingSpot(BlockPos pos, LongOpenHashSet tried) {
		LongOpenHashSet wanted = new LongOpenHashSet();
		blueprint().near(Vec3.atCenterOf(pos), 5, (p, target) -> {
			if (!target.wantsAir() && !target.matches(mc.level, p, mc.level.getBlockState(p))) wanted.add(p.asLong());
		});
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos s : BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(3, 1, 3))) {
			double h = Math.hypot(s.getX() - pos.getX(), s.getZ() - pos.getZ());
			if (h < 2 || h > 3.2 || tried.contains(s.asLong()) || wanted.contains(s.asLong()) || wanted.contains(s.above().asLong())) continue;
			if (!mc.level.getBlockState(s).getCollisionShape(mc.level, s).isEmpty()
				|| !mc.level.getBlockState(s.above()).getCollisionShape(mc.level, s.above()).isEmpty()
				|| mc.level.getBlockState(s.below()).getCollisionShape(mc.level, s.below()).isEmpty()) continue;
			double d = mc.player.position().distanceToSqr(Vec3.atBottomCenterOf(s));
			if (d < bestDistance) {
				best = s.immutable();
				bestDistance = d;
			}
		}
		return best;
	}

	/** {@code walk} with Baritone neither mining nor placing (it would dig up and build over the schematic), as it was after. */
	private static Step<Void> walkingOnly(Step<Void> walk) {
		Object[] before = new Object[2];
		return Step.run("Walk only", () -> {
			before[0] = Baritone.setSetting("allowBreak", false);
			before[1] = Baritone.setSetting("allowPlace", false);
		}).then(walk.finallyDo(() -> {
			if (before[0] != null) Baritone.setSetting("allowBreak", before[0]);
			if (before[1] != null) Baritone.setSetting("allowPlace", before[1]);
		})).recover(why -> Step.nothing());
	}

	// ---- restocking ---------------------------------------------------------------------------------------------

	/**
	 * Fetches a stack of the block most positions are waiting for: from the shulker boxes you carry, then the containers
	 * around you, nearest first, passing over those that had none lately. Never stuck or failed itself: what it couldn't
	 * find leaves the job waiting on the block, and it tries again a little later.
	 */
	private Step<Void> restock() {
		return Step.choose("Restock", () -> {
			Target target = missing();
			if (target == null) return Step.nothing();
			String key = target.describe();
			Predicate<ItemStack> what = s -> target.preference(s) >= 0;
			List<MaterialSource> sources = new ArrayList<>();
			if (fromShulkers.get()) sources.add(MaterialSource.shulkerBoxes());
			if (fromContainers.get()) for (BlockPos pos : containers(key)) sources.add(container(pos, key));
			if (sources.isEmpty()) return Step.nothing();
			int stack = new ItemStack(item(target)).getMaxStackSize();
			return Steps.acquire(what, Myriad.inventory().count(what) + stack, sources).stuckFor(1).recover(why -> Step.supply("Not found", () -> 0))
				.then(n -> Step.nothing());
		});
	}

	/** The target most of the build's positions have no item for, or null. */
	private @Nullable Target missing() {
		Build b = build();
		if (b == null) return null;
		Map<String, Target> byKind = new HashMap<>();
		Map<String, Integer> counts = new HashMap<>();
		for (Build.Step step : b.steps()) {
			if (step.status() != Build.Status.NO_ITEM) continue;
			String k = step.target().describe();
			byKind.putIfAbsent(k, step.target());
			counts.merge(k, 1, Integer::sum);
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> byKind.get(e.getKey())).orElse(null);
	}

	/** The item {@code target}'s block is placed with, for its stack size (stone, for a target with no one block). */
	private static Item item(Target target) {
		BlockState state = target.orientedState();
		return state == null || state.getBlock().asItem() == Items.AIR ? Items.STONE : state.getBlock().asItem();
	}

	/** Chests, barrels and shulker boxes in reach (or within Container Range with Walk To Blocks), nearest first, but not those empty of {@code key} lately. */
	private List<BlockPos> containers(String key) {
		double range = walk.get() ? containerRange.get() : running.placing().range();
		BlockPos at = mc.player.blockPosition();
		int r = (int) Math.ceil(range);
		long now = mc.player.tickCount;
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
			if (pos.distToCenterSqr(mc.player.getEyePosition()) > range * range) continue;
			Block block = mc.level.getBlockState(pos).getBlock();
			if (!(block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock)) continue;
			Long until = emptyFor.getOrDefault(pos.asLong(), Map.of()).get(key);
			if (until != null && until > now) continue;
			found.add(pos.immutable());
		}
		found.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(mc.player.getEyePosition())));
		return found;
	}

	/** The container at {@code pos} as a source, walked to first when out of reach; one that had none is remembered. */
	private MaterialSource container(BlockPos pos, String key) {
		return (what, count) -> {
			Step<Integer> take = MaterialSource.container(pos).fetch(what, count).then(moved -> Step.supply("Took " + moved, () -> {
				if (moved == 0) emptyFor.computeIfAbsent(pos.asLong(), p -> new HashMap<>()).put(key, (long) mc.player.tickCount + EMPTY_FOR_TICKS);
				return moved;
			}));
			boolean inReach = pos.distToCenterSqr(mc.player.getEyePosition()) <= Math.pow(running.placing().range(), 2);
			return inReach || !Baritone.isAvailable() ? take : walkingOnly(Steps.walkNear(pos, WALK_NEAR)).then(take);
		};
	}

	/** The running build, or null while off (and for the tick the job takes to start it). */
	public @Nullable Build build() {
		return build.get();
	}

	/** The build whose owner is a step of this module's job. */
	private @Nullable Build findBuild() {
		Job<Void> mine = job;
		if (mine == null) return null;
		for (Build b : Myriad.building().running()) {
			if (b.owner() instanceof Job<?> j && root(j) == mine) return b;
		}
		return null;
	}

	private static Job<?> root(Job<?> j) {
		while (j.parent() != null) j = j.parent();
		return j;
	}

	@Override
	public String hudInfo() {
		Build b = build();
		if (b == null) return null;
		long left = b.steps().stream().filter(s -> s.status() != Build.Status.DONE).count();
		return left == 0 ? null : Long.toString(left);
	}

	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		if (showPlan.get()) BuildPlanRenderer.draw(e, build(), look);
	}
}
