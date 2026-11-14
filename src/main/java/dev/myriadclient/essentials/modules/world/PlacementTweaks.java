package dev.myriadclient.essentials.modules.world;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InteractEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.ShapeBuilder;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small changes to placing blocks by hand, each while its bind is held: the face you aim at shows its four edges, the
 * one under the crosshair lit. Flexible Rotation places the block facing that edge (anywhere else: out of the face);
 * Offset places it in the space beside the usual one, towards that edge (anywhere else: where it usually goes). Both
 * binds held, both at once. Core's build engine places it, finding the click and the look that make the game place it
 * that way.
 */
public class PlacementTweaks extends Module {
	private final BoolSetting flexibleRotation = sgGeneral.bool("Flexible Rotation")
		.description("Hold its bind and right-click: the block faces the edge of the face under the crosshair, or out of the face anywhere else.")
		.defaultValue(true).build();
	private final KeybindSetting rotationBind = sgGeneral.keybind("Rotation Bind").description("Hold for Flexible Rotation.")
		.defaultValue(Keybind.key(InputConstants.KEY_LALT)).visible(flexibleRotation::get).build();
	private final BoolSetting offset = sgGeneral.bool("Offset")
		.description("Hold its bind and right-click: the block goes in the space beside the usual one, towards the edge under the crosshair.")
		.defaultValue(true).build();
	private final KeybindSetting offsetBind = sgGeneral.keybind("Offset Bind").description("Hold for Offset.")
		.defaultValue(Keybind.key(InputConstants.KEY_R)).visible(offset::get).build();
	private final BoolSetting airPlace = sgGeneral.bool("Air Place").description("Place against nothing when there's no block to click: a block "
		+ "that faces the way it's clicked (a hopper, a log, a shulker box) facing sideways, or an offset block with nothing beside it. 2b2t takes "
		+ "it; current Grim refuses it.").visible(() -> flexibleRotation.get() || offset.get()).build();

	private static final List<EnumProperty<Direction>> FACINGS = List.of(BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING,
		BlockStateProperties.FACING_HOPPER);
	private static final List<EnumProperty<Direction.Axis>> AXES = List.of(BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS);
	/** How far into a face, of its width, its edges reach; inside, the block faces out of the face. */
	private static final double EDGE = 0.25;
	/** The space between the overlay's pieces, and how much their inner corners are rounded. */
	private static final double GAP = 0.05, ROUNDING = 0.06;
	/** How far a diagonal gap's sides are from the corner-to-middle line, along an axis. */
	private static final double DIAGONAL = GAP / Math.sqrt(2);
	/** The edge piece along the low end of the face's second axis: outer corners, then the two inner ones. */
	private static final List<double[]> EDGE_PIECE = List.of(new double[]{DIAGONAL, 0}, new double[]{1 - DIAGONAL, 0},
		new double[]{1 - EDGE + GAP / 2 - DIAGONAL, EDGE - GAP / 2}, new double[]{EDGE - GAP / 2 + DIAGONAL, EDGE - GAP / 2});
	/** Points round each rounded corner. */
	private static final int ARC = 6;
	/** The overlay sits this far off the face, so the face doesn't show through it. */
	private static final double LIFT = 0.002;
	/** Ticks a placement may take before it's given up (it can't be placed that way from here). */
	private static final int GIVE_UP_TICKS = 20;

	private @Nullable Build placing;
	/** What the placement under way was meant to do, for the message if it can't. */
	private String placingWhat = "";
	private int placingTicks;

	public PlacementTweaks() {
		super(Tags.WORLD, "Placement Tweaks", "Places blocks by hand facing the way you choose.");
	}

	@Override
	protected void onDisable() {
		placing = null;
	}

	/** The block in your main hand, if it's one that can face more than one way; else null. */
	private @Nullable BlockItem orientable() {
		if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem item)) return null;
		BlockState s = item.getBlock().defaultBlockState();
		for (Property<?> p : FACINGS) if (s.hasProperty(p)) return item;
		for (Property<?> p : AXES) if (s.hasProperty(p)) return item;
		return null;
	}

	/** Whether Flexible Rotation is in use: its bind held, with a block that can face more than one way. */
	private boolean rotating() {
		return flexibleRotation.get() && rotationBind.get().isPressed() && orientable() != null;
	}

	/** Whether Offset is in use: its bind held. */
	private boolean offsetting() {
		return offset.get() && offsetBind.get().isPressed();
	}

	/** The face under the crosshair while either is in use, with a block in hand; else null. */
	private @Nullable BlockHitResult aimedFace() {
		if (!inGame() || mc.gui.screen() != null || !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return null;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || !rotating() && !offsetting()) return null;
		return hit;
	}

	/** Where the block goes for a click at {@code hit}: the usual space, or with Offset the one beside it towards the edge under the crosshair. */
	private BlockPos target(BlockHitResult hit, BlockPos usual) {
		Direction way = chosen(hit);
		return offsetting() && way != hit.getDirection() ? usual.relative(way) : usual;
	}

	/** Where on its face {@code hit} is, from 0 to 1 along the face's two axes (the first and second in {@link #axes}). */
	private static double[] onFace(BlockHitResult hit) {
		Direction.Axis[] axes = axes(hit.getDirection());
		Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
		return new double[]{local.get(axes[0]), local.get(axes[1])};
	}

	/** The two axes along a face. */
	private static Direction.Axis[] axes(Direction face) {
		return switch (face.getAxis()) {
			case X -> new Direction.Axis[]{Direction.Axis.Y, Direction.Axis.Z};
			case Y -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z};
			case Z -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Y};
		};
	}

	/** The way the block should face for a click at {@code hit}: the edge under the crosshair, or the face's own direction anywhere else. */
	private static Direction chosen(BlockHitResult hit) {
		double[] uv = onFace(hit);
		double u = uv[0], v = uv[1];
		Direction.Axis[] axes = axes(hit.getDirection());
		double toEdge = Math.min(Math.min(u, 1 - u), Math.min(v, 1 - v));
		if (toEdge >= EDGE) return hit.getDirection();
		if (toEdge == u) return Direction.fromAxisAndDirection(axes[0], Direction.AxisDirection.NEGATIVE);
		if (toEdge == 1 - u) return Direction.fromAxisAndDirection(axes[0], Direction.AxisDirection.POSITIVE);
		if (toEdge == v) return Direction.fromAxisAndDirection(axes[1], Direction.AxisDirection.NEGATIVE);
		return Direction.fromAxisAndDirection(axes[1], Direction.AxisDirection.POSITIVE);
	}

	/** {@code state} turned to face {@code facing} (its facing, or its axis), as far as it can; unchanged if it can't. */
	private static BlockState facing(BlockState state, Direction facing) {
		BlockState s = state;
		for (EnumProperty<Direction> p : FACINGS) if (s.hasProperty(p) && p.getPossibleValues().contains(facing)) s = s.setValue(p, facing);
		for (EnumProperty<Direction.Axis> p : AXES) if (s.hasProperty(p) && p.getPossibleValues().contains(facing.getAxis())) s = s.setValue(p, facing.getAxis());
		return s;
	}

	/** A right-click with a bind held: the block placed facing the chosen way or in the space beside, by the build engine. */
	@Subscribe
	private void onInteract(InteractEvent.Block e) {
		// Only the crosshair's own click: the build engine's clicks come through here too, each with a hit of its own.
		if (e.hand() != InteractionHand.MAIN_HAND || e.hit() != mc.hitResult) return;
		BlockHitResult hit = aimedFace();
		if (hit == null) return;
		if (placing != null) {
			e.cancel();
			return;
		}
		ItemStack stack = mc.player.getMainHandItem();
		BlockItem item = (BlockItem) stack.getItem();
		BlockPlaceContext ctx = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit);
		BlockPos usual = ctx.getClickedPos(), pos = target(hit, usual);
		Direction way = chosen(hit);
		Target target;
		if (rotating()) {
			BlockState vanilla = ctx.canPlace() ? item.getBlock().getStateForPlacement(ctx) : null;
			if (vanilla == null) vanilla = item.getBlock().defaultBlockState();
			BlockState wanted = facing(vanilla, way);
			// Vanilla places it that way already, or it can't face that way at all: the click goes ahead as usual.
			if (pos.equals(usual) && wanted.equals(vanilla)) return;
			target = Target.state(wanted);
			placingWhat = "facing " + way.getName().toLowerCase(Locale.ROOT);
		} else {
			// Offset from the middle of the face: the usual placement.
			if (pos.equals(usual)) return;
			target = Target.block(item.getBlock());
			placingWhat = "there";
		}
		e.cancel();
		if (!mc.level.getBlockState(pos).canBeReplaced()) {
			warn("Something's in the way");
			return;
		}
		Building.Options options = Building.Options.forServer();
		options = options.withPlacing(options.placing().withAirPlace(airPlace.get()));
		placing = Myriad.building().start(this, Blueprint.of(Map.of(pos, target)), options);
		placingTicks = 0;
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		Build build = placing;
		if (build == null) return;
		if (!build.isRunning()) {
			placing = null;
		} else if (++placingTicks > GIVE_UP_TICKS) {
			build.stop();
			placing = null;
			warn("Couldn't place it " + placingWhat + " from here" + (airPlace.get() ? "" : " (Air Place may let it)"));
		}
	}

	/**
	 * The aimed face's four edges, the one a click would use filled (none from the middle of the face), and with Offset
	 * the space the block will go in outlined.
	 */
	@Subscribe
	private void onRender(Render3DEvent e) {
		BlockHitResult hit = aimedFace();
		if (hit == null) return;
		Direction face = hit.getDirection();
		Direction.Axis[] axes = axes(face);
		BlockPos pos = hit.getBlockPos();
		// The plane the hit is on (a slab's top is half way up), lifted off it towards you.
		double plane = hit.getLocation().get(face.getAxis()) + face.getAxisDirection().getStep() * LIFT;
		int accent = Myriad.ui().theme().accent.argb();
		int line = ColorUtil.withAlpha(accent, 0xE0), fill = ColorUtil.withAlpha(accent, 0x70);
		ShapeBuilder shapes = e.shapes();
		Direction chosen = chosen(hit);
		// The edges in turn: 0 the low end of the face's second axis, 1 the high end of its first, 2 the
		// high end of its second, 3 the low end of its first (each the one before turned a quarter round the middle).
		List<double[]> edge = rounded(EDGE_PIECE, new double[]{0, 0, ROUNDING, ROUNDING});
		for (int i = 0; i < 4; i++) {
			Direction way = i == 0 ? Direction.fromAxisAndDirection(axes[1], Direction.AxisDirection.NEGATIVE)
				: i == 1 ? Direction.fromAxisAndDirection(axes[0], Direction.AxisDirection.POSITIVE)
				: i == 2 ? Direction.fromAxisAndDirection(axes[1], Direction.AxisDirection.POSITIVE)
				: Direction.fromAxisAndDirection(axes[0], Direction.AxisDirection.NEGATIVE);
			piece(shapes, pos, axes, face, plane, edge, line, chosen == way ? fill : 0);
			edge = edge.stream().map(c -> new double[]{1 - c[1], c[0]}).toList();
		}
		if (offsetting() && chosen != face) {
			BlockPos usual = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, mc.player.getMainHandItem(), hit).getClickedPos();
			shapes.boxLines(new AABB(target(hit, usual)), line, false);
		}
	}

	/** One piece of the overlay ({@code outline} as points on the face), outlined, and filled if {@code fill} isn't 0. */
	private static void piece(ShapeBuilder shapes, BlockPos pos, Direction.Axis[] axes, Direction face, double plane, List<double[]> outline, int line, int fill) {
		List<Vec3> points = outline.stream().map(c -> at(pos, axes, face, plane, c[0], c[1])).toList();
		for (int i = 0; i < points.size(); i++) shapes.line(points.get(i), points.get((i + 1) % points.size()), line, false);
		if (fill == 0) return;
		double u = 0, v = 0;
		for (double[] c : outline) {
			u += c[0];
			v += c[1];
		}
		Vec3 middle = at(pos, axes, face, plane, u / outline.size(), v / outline.size());
		for (int i = 0; i < points.size(); i++) {
			Vec3 b = points.get((i + 1) % points.size());
			shapes.quad(middle, points.get(i), b, b, fill, false);
		}
	}

	/**
	 * The convex outline {@code corners} (points on the face, going round) with each corner rounded by its radius in
	 * {@code radii} (0: left sharp), as points going round.
	 */
	private static List<double[]> rounded(List<double[]> corners, double[] radii) {
		List<double[]> points = new ArrayList<>();
		int n = corners.size();
		for (int i = 0; i < n; i++) {
			double[] p = corners.get(i), a = corners.get((i + n - 1) % n), b = corners.get((i + 1) % n);
			double r = radii[i];
			if (r <= 0) {
				points.add(p);
				continue;
			}
			double[] toA = unit(a[0] - p[0], a[1] - p[1]), toB = unit(b[0] - p[0], b[1] - p[1]);
			double half = Math.acos(Math.max(-1, Math.min(1, toA[0] * toB[0] + toA[1] * toB[1]))) / 2;
			double along = r / Math.tan(half);
			double[] mid = unit(toA[0] + toB[0], toA[1] + toB[1]);
			double[] centre = {p[0] + mid[0] * r / Math.sin(half), p[1] + mid[1] * r / Math.sin(half)};
			double from = Math.atan2(p[1] + toA[1] * along - centre[1], p[0] + toA[0] * along - centre[0]);
			double to = Math.atan2(p[1] + toB[1] * along - centre[1], p[0] + toB[0] * along - centre[0]);
			double sweep = Math.IEEEremainder(to - from, 2 * Math.PI);
			for (int k = 0; k <= ARC; k++) {
				double angle = from + sweep * k / ARC;
				points.add(new double[]{centre[0] + r * Math.cos(angle), centre[1] + r * Math.sin(angle)});
			}
		}
		return points;
	}

	private static double[] unit(double x, double y) {
		double length = Math.hypot(x, y);
		return new double[]{x / length, y / length};
	}

	/** The point {@code u}, {@code v} of the way along {@code face}'s axes of the block at {@code pos}, on {@code plane}. */
	private static Vec3 at(BlockPos pos, Direction.Axis[] axes, Direction face, double plane, double u, double v) {
		double[] c = new double[3];
		c[face.getAxis().ordinal()] = plane;
		c[axes[0].ordinal()] = pos.get(axes[0]) + u;
		c[axes[1].ordinal()] = pos.get(axes[1]) + v;
		return new Vec3(c[0], c[1], c[2]);
	}
}
