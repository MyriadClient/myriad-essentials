package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.BlockRules;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Printer's water: blocks the schematic has waterlogged get water poured into them from a bucket once they're
 * placed, and the schematic's water sources are poured where they go (against a block beside the spot, never one
 * that would take the water in itself, a slab or a fence); flowing water comes of them by itself. A source is poured
 * only once what holds it in is built (each side and below it a block, or more of the schematic's water), and as few
 * as can be: the spots that let the most of the rest fill in by themselves go first (both ends of a row of three, two
 * corners across a two by two), the bottom layer first, and a spot about to fill in by itself is waited for. The bucket is filled again only from water that fills itself back in (a source with two sources beside it
 * and something solid or more water under it: the middle of a row of three, any of a two by two), never from a lone
 * source or a pair, which would be gone for good. The bucket is used with the look on its target (the server aims a
 * bucket by your look), turned for it without moving your view. Nothing happens where water dries up (the Nether).
 */
final class Waterlogging {
	private static final Minecraft mc = Minecraft.getInstance();
	/** Ticks to wait for the bucket to change after a use before trying again. */
	private static final int ANSWER_TICKS = 20;

	private final Object owner;
	/** The tick the last use went, while its answer (the bucket changing) is awaited. */
	private long usedAt = Long.MIN_VALUE / 2;
	/** A use waiting for its look to be sent, since when, and how long it's waited for. */
	private boolean aiming;
	private long aimedAt;
	private static final int AIM_TICKS = 10;
	/** Inventory slot of a bucket borrowed into the hotbar, and the hotbar slot it went to (-1 for none). */
	private int borrowedFrom = -1, borrowedInto = -1;

	Waterlogging(Object owner) {
		this.owner = owner;
	}

	/**
	 * One step of filling the waterlogged blocks of {@code blueprint} within {@code range}: pours into one, or fills the
	 * bucket first. Call each tick the build has nothing else to place in reach. Returns whether there's water work in
	 * reach (so the caller knows the build isn't idle).
	 */
	boolean tick(Blueprint blueprint, double range) {
		if (mc.player == null || mc.level == null || mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, mc.player.blockPosition())) return false;
		// A look another turn took over never goes: tried again after a while.
		if (aiming && mc.player.tickCount - aimedAt > AIM_TICKS) aiming = false;
		if (aiming || mc.player.tickCount - usedAt < ANSWER_TICKS && !answered()) return true;
		Pour target = nextPour(blueprint, range);
		if (target == null) {
			release();
			return false;
		}
		// Water filling in by itself: waited for.
		if (target.point() == null) return true;
		Predicate<ItemStack> water = s -> s.is(Items.WATER_BUCKET), empty = s -> s.is(Items.BUCKET);
		int slot = hotbarSlot(water);
		if (slot >= 0) {
			aimAndUse(slot, target.point());
			return true;
		}
		// No bucket, or no water in reach that fills back in: nothing to do here, the build may walk on.
		slot = hotbarSlot(empty);
		if (slot < 0) return false;
		Vec3 source = infiniteSource(range);
		if (source == null) return false;
		aimAndUse(slot, source);
		return true;
	}

	/** Lets go of a borrowed bucket. */
	void release() {
		if (borrowedInto >= 0) Myriad.inventory().giveBack(owner);
		borrowedFrom = borrowedInto = -1;
		aiming = false;
	}

	/** The bucket in hand changed since the last use (another, or one fewer of a stack): the server answered it. */
	private boolean answered() {
		ItemStack now = mc.player.getInventory().getItem(lastSlot);
		return lastHeld != null && (!now.is(lastHeld) || now.getCount() != lastCount);
	}

	private net.minecraft.world.item.@Nullable Item lastHeld;
	private int lastSlot, lastCount;

	/** A hotbar slot with what {@code what} takes, borrowing it from the inventory if need be (-1 if there's none yet). */
	private int hotbarSlot(Predicate<ItemStack> what) {
		var inv = mc.player.getInventory();
		if (borrowedInto >= 0 && what.test(inv.getItem(borrowedInto))) return Myriad.inventory().borrow(owner, borrowedInto, s -> false);
		for (int i = 0; i < 9; i++) if (what.test(inv.getItem(i))) return i;
		for (int i = 9; i < 36; i++) {
			if (!what.test(inv.getItem(i))) continue;
			int into = Myriad.inventory().borrow(owner, i, s -> false);
			if (into >= 0) {
				borrowedFrom = i;
				borrowedInto = into;
			}
			return into;
		}
		return -1;
	}

	/** Turns to {@code point} (the look only the server sees) and uses hotbar {@code slot}'s bucket once that look has gone. */
	private void aimAndUse(int slot, @Nullable Vec3 point) {
		if (point == null) return;
		float[] r = MathUtil.anglesTo(mc.player.getEyePosition(), point);
		aiming = true;
		aimedAt = mc.player.tickCount;
		Myriad.rotations().request(owner, r[0], r[1], Rotations.PRIORITY_HIGH, Rotations.Options.INSTANT, () -> mc.execute(() -> {
			aiming = false;
			if (mc.player == null) return;
			lastSlot = slot;
			lastHeld = mc.player.getInventory().getItem(slot).getItem();
			lastCount = mc.player.getInventory().getItem(slot).getCount();
			usedAt = mc.player.tickCount;
			float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
			Myriad.inventory().silentSwap(slot, () -> {
				// The game works out where the bucket goes from your look: the one the server has for this use.
				mc.player.setYRot(r[0]);
				mc.player.setXRot(r[1]);
				try {
					mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
				} finally {
					mc.player.setYRot(yaw);
					mc.player.setXRot(pitch);
				}
			});
		}));
	}

	/** A position to pour water into, and the point the look goes to for it (null: wait, water is filling in by itself). */
	private record Pour(BlockPos pos, @Nullable Vec3 point) {}

	private static final Pour WAIT = new Pour(BlockPos.ZERO, null);
	/** Ticks a spot expected to fill in by itself is waited for before it's poured after all. */
	private static final int FORM_TICKS = 15;
	/** Since when (tick) each spot expected to fill in by itself has been waited for. */
	private final Long2LongOpenHashMap formingSince = new Long2LongOpenHashMap();

	/**
	 * What to pour next: the nearest block in reach the schematic has waterlogged where the right block already stands
	 * dry; else the best of its water sources to pour (see the class); {@link #WAIT} while sources fill in by
	 * themselves; null if there's nothing to do.
	 */
	private @Nullable Pour nextPour(Blueprint blueprint, double range) {
		Vec3 eyes = mc.player.getEyePosition();
		double r2 = range * range;
		Pour[] logged = {null};
		double[] loggedD = {Double.MAX_VALUE};
		// The schematic's water near (a little past reach, for what holds the spots in reach in), and its sources not
		// there yet.
		Long2ObjectOpenHashMap<BlockState> wanted = new Long2ObjectOpenHashMap<>();
		List<BlockPos> dry = new ArrayList<>();
		blueprint.near(eyes, range + 2, (pos, target) -> {
			BlockState want = target.orientedState();
			if (want == null) return;
			BlockState here = mc.level.getBlockState(pos);
			if (want.getBlock() instanceof LiquidBlock) {
				if (!want.getFluidState().getType().isSame(Fluids.WATER)) return;
				wanted.put(pos.asLong(), want);
				if (want.getFluidState().isSource() && (here.isAir() || here.getBlock() instanceof LiquidBlock
					&& here.getFluidState().getType().isSame(Fluids.WATER) && !here.getFluidState().isSource())) dry.add(pos.immutable());
				return;
			}
			if (!want.hasProperty(BlockStateProperties.WATERLOGGED) || !want.getValue(BlockStateProperties.WATERLOGGED)) return;
			if (!here.is(want.getBlock()) || here.getValue(BlockStateProperties.WATERLOGGED) || !BlockRules.placedMatches(here, want)) return;
			double d = eyes.distanceToSqr(Vec3.atCenterOf(pos));
			if (d >= loggedD[0] || d > r2) return;
			Vec3 point = pourPoint(pos, range);
			if (point == null) return;
			logged[0] = new Pour(pos.immutable(), point);
			loggedD[0] = d;
		});
		if (logged[0] != null) return logged[0];
		if (dry.isEmpty()) {
			formingSince.clear();
			return null;
		}
		Long2BooleanOpenHashMap sourceCache = new Long2BooleanOpenHashMap();
		// Spots filling in from what's there already: waited for a while, not poured.
		LongOpenHashSet forming = fillsIn(dry, null, sourceCache);
		long now = mc.player.tickCount;
		formingSince.keySet().removeIf(k -> !forming.contains(k));
		List<BlockPos> candidates = new ArrayList<>();
		LongOpenHashSet held = new LongOpenHashSet(), leaks = new LongOpenHashSet();
		for (BlockPos pos : dry) {
			long key = pos.asLong();
			if (forming.contains(key)) {
				formingSince.putIfAbsent(key, now);
				if (now - formingSince.get(key) < FORM_TICKS) continue;
			}
			if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > r2 || !heldIn(pos, wanted, held, leaks)) continue;
			candidates.add(pos);
		}
		if (candidates.isEmpty()) return forming.isEmpty() ? null : WAIT;
		// Most of the rest filling in after it first, then the lowest, then the fewest water spots beside it (ends and
		// corners), then the nearest.
		Object2IntOpenHashMap<BlockPos> gain = new Object2IntOpenHashMap<>();
		for (BlockPos c : candidates) gain.put(c, fillsIn(dry, c, sourceCache).size());
		candidates.sort(Comparator.<BlockPos>comparingInt(c -> -gain.getInt(c)).thenComparingInt(BlockPos::getY)
			.thenComparingInt(c -> waterBeside(c, wanted)).thenComparingDouble(c -> eyes.distanceToSqr(Vec3.atCenterOf(c))));
		for (BlockPos c : candidates) {
			Vec3 point = sourcePoint(c, range);
			if (point != null) return new Pour(c, point);
		}
		return forming.isEmpty() ? null : WAIT;
	}

	/**
	 * The spots of {@code dry} that would turn into sources by themselves with {@code poured} poured (or as things are, if
	 * null): two sources beside one and a block or a source under it, over and over as they form.
	 */
	private static LongOpenHashSet fillsIn(List<BlockPos> dry, @Nullable BlockPos poured, Long2BooleanOpenHashMap cache) {
		LongOpenHashSet added = new LongOpenHashSet();
		if (poured != null) added.add(poured.asLong());
		LongOpenHashSet formed = new LongOpenHashSet();
		boolean changed = true;
		while (changed) {
			changed = false;
			for (BlockPos pos : dry) {
				long key = pos.asLong();
				if (added.contains(key)) continue;
				int beside = 0;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					long n = pos.relative(d).asLong();
					if (added.contains(n) || cache.computeIfAbsent(n, k -> isSource(BlockPos.of(k)))) beside++;
				}
				if (beside < 2) continue;
				BlockPos below = pos.below();
				if (!added.contains(below.asLong()) && !mc.level.getBlockState(below).isSolid() && !cache.computeIfAbsent(below.asLong(), k -> isSource(BlockPos.of(k)))) continue;
				added.add(key);
				formed.add(key);
				changed = true;
			}
		}
		return formed;
	}

	/**
	 * Whether water poured at {@code pos} stays in the schematic's water: the water it joins up with (the schematic's,
	 * across and down and up from it) has a block on every side and under it wherever it isn't more of that water.
	 * Otherwise what holds it in isn't built yet and it would run out over the rest.
	 */
	private static boolean heldIn(BlockPos pos, Long2ObjectOpenHashMap<BlockState> wanted, LongOpenHashSet held, LongOpenHashSet leaks) {
		long start = pos.asLong();
		if (held.contains(start)) return true;
		if (leaks.contains(start)) return false;
		LongOpenHashSet body = new LongOpenHashSet();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		body.add(start);
		queue.add(pos);
		boolean ok = true;
		while (!queue.isEmpty()) {
			BlockPos at = queue.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = at.relative(d);
				long key = n.asLong();
				if (wanted.containsKey(key)) {
					if (body.add(key)) queue.add(n);
					continue;
				}
				if (d == Direction.UP) continue;
				BlockState s = mc.level.getBlockState(n);
				if (!s.isSolid() && !(s.getBlock() instanceof LiquidBlockContainer)) ok = false;
			}
		}
		(ok ? held : leaks).addAll(body);
		return ok;
	}

	/** How many of the schematic's water spots are beside {@code pos} (across). */
	private static int waterBeside(BlockPos pos, Long2ObjectOpenHashMap<BlockState> wanted) {
		int n = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (wanted.containsKey(pos.relative(d).asLong())) n++;
		return n;
	}

	/**
	 * A point on a block beside {@code pos} (on its face towards it) that a look from your eyes reaches first, for pouring
	 * a source into {@code pos}: the bucket puts it beside the block the look meets. Not a block that takes water in
	 * itself (a slab, stairs, a fence): the bucket would fill that instead. Nearest first; null if there's none.
	 */
	private @Nullable Vec3 sourcePoint(BlockPos pos, double range) {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 best = null;
		double bestD = range * range;
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			BlockState state = mc.level.getBlockState(n);
			if (state.getBlock() instanceof LiquidBlockContainer) continue;
			var shape = state.getShape(mc.level, n);
			if (shape.isEmpty()) continue;
			var box = shape.bounds();
			Direction face = d.getOpposite();
			// The face's plane, and points across it: its middle, then nearer each corner.
			for (double[] o : new double[][]{{0.5, 0.5}, {0.25, 0.25}, {0.75, 0.75}, {0.25, 0.75}, {0.75, 0.25}}) {
				double x = across(face.getStepX(), box.minX, box.maxX, o[0]), y = across(face.getStepY(), box.minY, box.maxY, face.getAxis() == Direction.Axis.X ? o[0] : o[1]);
				double z = across(face.getStepZ(), box.minZ, box.maxZ, o[1]);
				Vec3 p = new Vec3(n.getX() + x, n.getY() + y, n.getZ() + z);
				double dist = eyes.distanceToSqr(p);
				if (dist >= bestD) continue;
				BlockHitResult hit = mc.level.clip(new ClipContext(eyes, eyes.add(p.subtract(eyes).scale(1.05)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(n) || hit.getDirection() != face) continue;
				best = p;
				bestD = dist;
				break;
			}
		}
		return best;
	}

	/** Along one axis of a face: on its plane where the face points that way ({@code step} ±1), else {@code f} across it. */
	private static double across(int step, double min, double max, double f) {
		return step > 0 ? max : step < 0 ? min : min + (max - min) * f;
	}

	/** A point on the block at {@code pos} that a look from your eyes reaches first, for pouring into it; null if none. */
	private @Nullable Vec3 pourPoint(BlockPos pos, double range) {
		Vec3 eyes = mc.player.getEyePosition();
		var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
		if (shape.isEmpty()) return null;
		var box = shape.bounds();
		Vec3 centre = box.getCenter().add(pos.getX(), pos.getY(), pos.getZ());
		for (Vec3 p : new Vec3[]{centre, centre.add(0, (box.maxY - box.minY) * 0.3, 0), centre.add(0, -(box.maxY - box.minY) * 0.3, 0)}) {
			if (eyes.distanceToSqr(p) > range * range) continue;
			BlockHitResult hit = mc.level.clip(new ClipContext(eyes, eyes.add(p.subtract(eyes).scale(1.05)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return p;
		}
		return null;
	}

	/**
	 * A point on the surface of a water source in reach that fills itself back in, which a look from your eyes reaches
	 * before any other water or block, nearest first; null if there's none.
	 */
	private @Nullable Vec3 infiniteSource(double range) {
		Vec3 eyes = mc.player.getEyePosition();
		BlockPos at = mc.player.blockPosition();
		int r = (int) Math.ceil(range);
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
			if (!refills(pos)) continue;
			for (double[] o : new double[][]{{0.5, 0.5}, {0.3, 0.3}, {0.7, 0.7}, {0.3, 0.7}, {0.7, 0.3}}) {
				Vec3 p = new Vec3(pos.getX() + o[0], pos.getY() + 0.85, pos.getZ() + o[1]);
				double d = eyes.distanceToSqr(p);
				if (d > range * range || d >= bestD) continue;
				BlockHitResult hit = mc.level.clip(new ClipContext(eyes, eyes.add(p.subtract(eyes).scale(1.05)), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
				best = p;
				bestD = d;
				break;
			}
		}
		return best;
	}

	/**
	 * Whether taking the water source at {@code pos} leaves it filling back in: a plain source (not a waterlogged block)
	 * with at least two sources beside it and something solid or another source under it.
	 */
	private static boolean refills(BlockPos pos) {
		if (!isSource(pos)) return false;
		int beside = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (isSource(pos.relative(d))) beside++;
		if (beside < 2) return false;
		BlockState below = mc.level.getBlockState(pos.below());
		return below.isSolid() || isSource(pos.below());
	}

	private static boolean isSource(BlockPos pos) {
		BlockState s = mc.level.getBlockState(pos);
		return s.getBlock() instanceof LiquidBlock && s.getFluidState().isSourceOfType(Fluids.WATER);
	}
}
