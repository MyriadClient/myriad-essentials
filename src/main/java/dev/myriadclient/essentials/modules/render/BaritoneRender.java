package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.Ghosts;
import dev.myriadclient.api.render.HighlightSettings;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.Baritone;
import dev.myriadclient.api.util.TickCached;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws where Baritone is going in place of Baritone's own lines and boxes (turned off while this is on, and back on
 * after): its path as a smooth line with rounded corners and a gradient flowing along it, the blocks it will break and
 * place on the way, and its goal as a ghost of you standing there: your skin, armour, cape or elytra and what you hold,
 * faded, outlined by the highlight shader (seen through walls by default). The blocks are highlighted the same way.
 * Read through Baritone's public API, so it follows any Baritone build; the blocks to break and place are worked out
 * from the path (Baritone's own lists aren't public).
 */
public class BaritoneRender extends Module {
	public enum GoalLook {
		GHOST, BOX, NONE
	}

	/** How many nodes ahead the blocks to break and place are shown for. */
	private static final int BLOCKS_AHEAD = 40;

	private final SettingGroup sgPath = settings.group("Path");
	private final BoolSetting path = sgPath.bool("Path").description("Draw the path Baritone walks.").defaultValue(true).build();
	private final ColorSetting pathStart = sgPath.color("Start").description("One end of the gradient.")
		.defaultValue(SettingColor.accent()).visible(path::get).build();
	private final ColorSetting pathEnd = sgPath.color("End").description("The other end of the gradient.")
		.defaultValue(SettingColor.role(SettingColor.Mode.SECONDARY)).visible(path::get).build();
	private final DoubleSetting flow = sgPath.doubleSetting("Flow").description("How fast the gradient flows along the path, in blocks a second (0 holds it still).")
		.defaultValue(6).range(0, 40).decimals(1).visible(path::get).build();
	private final DoubleSetting width = sgPath.doubleSetting("Width").description("Line width, in pixels.")
		.defaultValue(2.5).range(0.5, 8).decimals(1).visible(path::get).build();
	private final IntSetting rounding = sgPath.intSetting("Rounding").description("How round the corners are: 0 draws Baritone's straight segments.")
		.defaultValue(3).range(0, 5).visible(path::get).build();
	private final BoolSetting pathThroughWalls = sgPath.bool("Through Walls").description("Draw the path over blocks in the way.")
		.visible(path::get).build();

	private final SettingGroup sgBlocks = settings.group("Blocks");
	private final BoolSetting blocks = sgBlocks.bool("Blocks").description("Mark the blocks Baritone will break and place along the path ahead.")
		.defaultValue(true).build();
	private final ColorSetting breakColor = sgBlocks.color("Break").description("Blocks in the way it will mine.")
		.defaultValue(SettingColor.role(SettingColor.Mode.RED)).visible(blocks::get).build();
	private final ColorSetting placeColor = sgBlocks.color("Place").description("Gaps it will fill to walk over (bridging, pillaring).")
		.defaultValue(SettingColor.role(SettingColor.Mode.BLUE)).visible(blocks::get).build();
	private final HighlightSettings blockStyle = new HighlightSettings(sgBlocks, blocks::get, false);

	private final SettingGroup sgGoal = settings.group("Goal");
	private final EnumSetting<GoalLook> goal = sgGoal.enumSetting("Goal", GoalLook.GHOST)
		.description("Ghost: you, standing at the goal. Box: the goal block. None: nothing.").build();
	private final DoubleSetting opacity = sgGoal.doubleSetting("Opacity").description("How solid the ghost is.")
		.defaultValue(0.35).range(0.05, 1).decimals(2).visible(() -> goal.get() == GoalLook.GHOST).build();
	private final ColorSetting goalColor = sgGoal.color("Highlight").description("The ghost's (or the box's) highlight.")
		.defaultValue(SettingColor.accent()).visible(() -> goal.get() != GoalLook.NONE).build();
	private final BoolSetting bob = sgGoal.bool("Float").description("The ghost hovers a little, bobbing, and its highlight pulses.").defaultValue(true)
		.visible(() -> goal.get() == GoalLook.GHOST).build();
	private final HighlightSettings goalStyle = new HighlightSettings(sgGoal, () -> goal.get() != GoalLook.NONE, true);

	/** The path's points this tick, rounded; and the blocks ahead to break and to place. */
	private final TickCached<List<Vec3>> points = TickCached.of(this::roundedPath);
	private final TickCached<List<BlockPos>[]> work = TickCached.of(this::blocksAhead);
	/** The way the ghost faces now (turned smoothly towards where it should), or NaN before the first frame. */
	private float ghostYaw = Float.NaN;
	/** Baritone's own drawing as it was before this turned it off (null: not changed). */
	private Object renderPathBefore, renderGoalBefore, renderBoxesBefore;

	public BaritoneRender() {
		super(Tags.RENDER, "Baritone Render", "Draw Baritone's path, its work and its goal your way: a flowing line, marked blocks and a ghost of you.");
	}

	@Override
	protected void onEnable() {
		renderPathBefore = Baritone.setSetting("renderPath", false);
		renderGoalBefore = Baritone.setSetting("renderGoal", false);
		renderBoxesBefore = Baritone.setSetting("renderSelectionBoxes", false);
	}

	@Override
	protected void onDisable() {
		// Back as they were, unless something turned them on again meanwhile.
		putBack("renderPath", renderPathBefore);
		putBack("renderGoal", renderGoalBefore);
		putBack("renderSelectionBoxes", renderBoxesBefore);
		renderPathBefore = renderGoalBefore = renderBoxesBefore = null;
	}

	private static void putBack(String name, Object before) {
		if (before != null && Boolean.FALSE.equals(Baritone.getSetting(name))) Baritone.setSetting(name, before);
	}

	@Subscribe(inGame = true)
	private void onRender(Render3DEvent e) {
		if (path.get()) drawPath(e.tickDelta());
		if (goal.get() == GoalLook.GHOST) for (Baritone.GoalSpot spot : Baritone.goalSpots()) drawGhost(e, spot);
	}

	/** The blocks to break and place, and a Box goal, through the highlight shader. */
	@Subscribe(inGame = true)
	private void onHighlight(HighlightEvent.Shapes e) {
		if (blocks.get()) {
			List<BlockPos>[] w = work.get();
			for (BlockPos p : w[0]) e.block(p, blockStyle.style(), breakColor.get().argb());
			for (BlockPos p : w[1]) e.box(new AABB(p), blockStyle.style(), placeColor.get().argb());
		}
		if (goal.get() == GoalLook.BOX) {
			for (Baritone.GoalSpot spot : Baritone.goalSpots()) {
				int y = spot.column() ? (int) columnFloor(spot.x(), spot.z()) : spot.y();
				e.box(new AABB(new BlockPos(spot.x(), y, spot.z())), goalStyle.style(), goalColor.get().argb());
			}
		}
	}

	private float seconds(float tickDelta) {
		return (mc.player.tickCount + tickDelta) / 20f;
	}

	// ---- path -------------------------------------------------------------------------------------------------------

	private void drawPath(float tickDelta) {
		List<Vec3> pts = points.get();
		if (pts.size() < 2) return;
		int from = pathStart.get().argb(), to = pathEnd.get().argb();
		// The gradient runs start, end, start again every PERIOD blocks, shifted along by Flow.
		double period = 24, shift = flow.get() * seconds(tickDelta);
		Renderer3D.lineWidth(width.getFloat());
		double done = 0;
		for (int i = 1; i < pts.size(); i++) {
			int a = gradient(from, to, (done - shift) / period);
			done += pts.get(i).distanceTo(pts.get(i - 1));
			int b = gradient(from, to, (done - shift) / period);
			Renderer3D.line(pts.get(i - 1), pts.get(i), a, b, pathThroughWalls.get());
		}
	}

	/** {@code from} to {@code to} and back over each whole number of {@code t}. */
	private static int gradient(int from, int to, double t) {
		double f = t - Math.floor(t);
		return ARGB.srgbLerp((float) (f < 0.5 ? f * 2 : 2 - f * 2), from, to);
	}

	/** Baritone's path from where it is, a little above each block's floor, its corners cut {@link #rounding} times. */
	private List<Vec3> roundedPath() {
		List<BlockPos> nodes = Baritone.path();
		List<Vec3> pts = new ArrayList<>(nodes.size());
		for (BlockPos b : nodes) pts.add(new Vec3(b.getX() + 0.5, b.getY() + 0.1, b.getZ() + 0.5));
		for (int r = 0; r < rounding.get() && pts.size() > 2; r++) pts = chaikin(pts);
		return pts;
	}

	/** One round of Chaikin's corner cutting: each corner is replaced by points a quarter of the way along its two sides. */
	private static List<Vec3> chaikin(List<Vec3> pts) {
		List<Vec3> out = new ArrayList<>(pts.size() * 2);
		out.add(pts.getFirst());
		for (int i = 0; i < pts.size() - 1; i++) {
			Vec3 p = pts.get(i), q = pts.get(i + 1);
			out.add(p.lerp(q, 0.25));
			out.add(p.lerp(q, 0.75));
		}
		out.add(pts.getLast());
		return out;
	}

	// ---- blocks to break and place ----------------------------------------------------------------------------------

	/**
	 * The next {@link #BLOCKS_AHEAD} nodes' work, as Baritone would see it: anything solid where your body goes (feet and
	 * head, and over your head where it climbs) is mined; a gap under a node it walks or climbs onto is filled.
	 */
	@SuppressWarnings("unchecked")
	private List<BlockPos>[] blocksAhead() {
		List<BlockPos> nodes = Baritone.path();
		Set<BlockPos> toBreak = new LinkedHashSet<>(), toPlace = new LinkedHashSet<>();
		for (int i = 1; i < Math.min(nodes.size(), BLOCKS_AHEAD); i++) {
			BlockPos from = nodes.get(i - 1), to = nodes.get(i);
			for (BlockPos body : new BlockPos[]{to, to.above(), to.getY() > from.getY() ? from.above(2) : null}) {
				if (body != null && !mc.level.getBlockState(body).getCollisionShape(mc.level, body).isEmpty()) toBreak.add(body);
			}
			BlockPos floor = to.below();
			if (to.getY() >= from.getY() && mc.level.getBlockState(floor).canBeReplaced()) toPlace.add(floor);
		}
		return new List[]{new ArrayList<>(toBreak), new ArrayList<>(toPlace)};
	}

	// ---- goal -------------------------------------------------------------------------------------------------------

	/** Where a column goal stands: the ground there if its chunk is loaded, else your own height. */
	private double columnFloor(int x, int z) {
		if (mc.level.hasChunk(x >> 4, z >> 4)) return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		return mc.player.getY();
	}

	/**
	 * You at the goal, as you're drawn this frame (skin, armour, cape or elytra, what you hold) but standing still,
	 * facing the way you'd arrive: faded and highlighted through {@link Ghosts}.
	 */
	private void drawGhost(Render3DEvent e, Baritone.GoalSpot spot) {
		if (!(mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof LivingEntityRenderer<?, ?, ?>)) return;
		double y = spot.column() ? columnFloor(spot.x(), spot.z()) : spot.y();
		Vec3 feet = new Vec3(spot.x() + 0.5, y, spot.z() + 0.5);
		LivingEntityRenderState state = (LivingEntityRenderState) mc.getEntityRenderDispatcher().extractEntity(mc.player, e.tickDelta());
		float time = seconds(e.tickDelta());
		Vec3 at = feet.add(0, bob.get() ? 0.15 + 0.1 * Mth.sin(time * 2f) : 0, 0);
		float want = facing(feet);
		ghostYaw = Float.isNaN(ghostYaw) ? want : ghostYaw + Mth.wrapDegrees(want - ghostYaw) * 0.12f;
		state.pose = Pose.STANDING;
		state.walkAnimationPos = 0;
		state.walkAnimationSpeed = 0;
		state.yRot = 0;
		state.xRot = 0;
		state.bodyRot = ghostYaw;
		state.deathTime = 0;
		state.isAutoSpinAttack = false;
		state.isInvisible = false;
		state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
		if (state instanceof HumanoidRenderState h) {
			h.isCrouching = false;
			h.isFallFlying = false;
			h.isVisuallySwimming = false;
			h.swimAmount = 0;
			h.isPassenger = false;
			h.isUsingItem = false;
		}
		int color = goalColor.get().argb();
		if (bob.get()) color = ARGB.multiplyAlpha(color, 0.7f + 0.3f * Mth.sin(time * 3f));
		Ghosts.submit(e, state, at, opacity.getFloat(), goalStyle.style(), color);
	}

	/**
	 * The yaw the ghost should face: along the last few blocks of the path into the goal when the path gets there, else
	 * from you towards it (the way you'd arrive).
	 */
	private float facing(Vec3 feet) {
		List<Vec3> pts = points.get();
		Vec3 from = mc.player.position();
		if (!pts.isEmpty() && pts.getLast().distanceToSqr(feet.x, pts.getLast().y, feet.z) < 4) {
			for (int i = pts.size() - 1; i >= 0; i--) {
				if (pts.get(i).distanceToSqr(feet.x, pts.get(i).y, feet.z) > 9) {
					from = pts.get(i);
					break;
				}
			}
		}
		double dx = feet.x - from.x, dz = feet.z - from.z;
		if (dx * dx + dz * dz < 1e-4) return Float.isNaN(ghostYaw) ? mc.player.getYRot() : ghostYaw;
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}
}
