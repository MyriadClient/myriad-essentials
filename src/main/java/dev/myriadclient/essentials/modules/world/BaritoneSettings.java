package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.render.Ghosts;
import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.util.TickCached;
import java.util.Set;
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
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.Baritone;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.awt.Color;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Baritone's settings in Myriad's menu, made from Baritone's own list (so new ones show up by themselves), sorted into
 * groups. While it's on, every setting changed here from Baritone's default is set in Baritone, for now: Baritone's
 * settings file isn't touched, and turning it off puts Baritone's own values back (unless something else changed them
 * since, like a {@code #set}). Settings left at Baritone's default leave Baritone's own value alone. Kept per profile
 * like any module's, so control panels and shared configs can carry a Baritone setup. Only registered with Baritone
 * installed.
 */
public class BaritoneSettings extends Module {
	/** Groups by the first rule a setting's name matches, in this order; the rest go to Pathing. */
	private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

	static {
		GROUPS.put("Breaking & Placing", List.of("allowbreak", "allowplace", "blocksto", "avoidbreaking", "throwaway", "autotool", "reach",
			"blockbreakspeed", "rightclickspeed", "itemsaver", "silktouch", "sword", "walkwhilebreaking", "inventory", "penalty"));
		GROUPS.put("Movement", List.of("allow", "assume", "sprint", "parkour", "overshoot", "maxfall", "walkon", "vines", "potion",
			"enterportal", "avoidance", "strictliquid"));
		GROUPS.put("Mining", List.of("mine", "mining", "exposedores", "explore"));
		GROUPS.put("Building", List.of("build", "schematic", "layer", "mapart", "okif", "incorrect", "correctblock", "breakfromabove"));
		GROUPS.put("Farming", List.of("farm", "replant"));
		GROUPS.put("Following", List.of("follow"));
		GROUPS.put("Looking", List.of("look", "anticheat"));
		GROUPS.put("Elytra", List.of("elytra"));
		GROUPS.put("Rendering", List.of("render", "color", "opacity", "linewidth", "fadepath", "selection", "ylevelbox"));
		GROUPS.put("Chat", List.of("chat", "prefix", "echo", "censor", "logas", "verbose", "notification", "messagetag"));
	}

	/** One setting here and the Baritone setting it stands for. */
	private record Link(String name, Setting<?> setting, Function<Object, Object> toBaritone, boolean asText) {
	}

	/** How the goal is drawn when Custom Render is on. */
	public enum GoalLook {
		GHOST, BOX, NONE
	}

	private final List<Link> links = new ArrayList<>();
	private final BoolSetting custom;
	private final Renderer renderer;
	private boolean rendering;
	/** Baritone's own values for the settings set while on, to put back; and what was set, to tell if it changed since. */
	private final Map<String, Object> before = new HashMap<>(), set = new HashMap<>();

	public BaritoneSettings(List<Baritone.SettingInfo> baritone) {
		super(Tags.WORLD, "Baritone", "Baritone's settings: while on, what you change here from Baritone's defaults is set in Baritone, "
			+ "and Custom Render draws its path, work and goal Myriad's way.");
		Map<String, SettingGroup> groups = new LinkedHashMap<>();
		for (String g : GROUPS.keySet()) groups.put(g, settings.group(g));
		SettingGroup pathing = settings.group("Pathing");
		for (Baritone.SettingInfo info : baritone) {
			Link link = link(info, groups.getOrDefault(groupOf(info.name()), pathing));
			if (link == null) continue;
			links.add(link);
			link.setting().onChanged(v -> {
				if (isEnabled()) apply(link);
			});
		}
		// Last, after Baritone's own groups.
		custom = settings.group("Render").bool("Custom Render")
			.description("Draw Baritone's path, its work and its goal your way while this is on: a flowing line, marked blocks and a ghost of you.")
			.build();
		renderer = new Renderer();
		custom.onChanged(v -> render());
	}

	private static String groupOf(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (var g : GROUPS.entrySet()) for (String part : g.getValue()) if (lower.contains(part)) return g.getKey();
		return "Pathing";
	}

	/** "allowBreak" as "Allow Break". */
	private static String title(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (i == 0) sb.append(Character.toUpperCase(c));
			else if (Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) sb.append(' ').append(c);
			else sb.append(c);
		}
		return sb.toString();
	}

	/** This module's setting for one of Baritone's, as near its type as Myriad has; null if it has no default to show. */
	@SuppressWarnings("unchecked")
	private static Link link(Baritone.SettingInfo info, SettingGroup group) {
		String title = title(info.name()), about = "Baritone's " + info.name() + ".";
		Object def = info.defaultValue();
		Class<?> type = info.valueClass();
		if (type == Boolean.class) {
			return new Link(info.name(), group.bool(title).description(about).defaultValue((Boolean) def).build(), v -> v, false);
		}
		if (type == Integer.class) {
			return new Link(info.name(), group.integer(title).description(about).defaultValue((Integer) def).typed().build(), v -> v, false);
		}
		if (type == Double.class || type == Float.class) {
			boolean f = type == Float.class;
			return new Link(info.name(), group.decimal(title).description(about).defaultValue(((Number) def).doubleValue()).decimals(3).typed().build(),
				v -> f ? (Object) ((Number) v).floatValue() : v, false);
		}
		if (type == Color.class) {
			return new Link(info.name(), group.color(title).description(about).defaultValue(SettingColor.of(((Color) def).getRGB())).build(),
				v -> new Color(((SettingColor) v).argb(), true), false);
		}
		Class<?> element = listOf(info.genericType());
		if (element == Block.class) {
			return new Link(info.name(), group.blocks(title).description(about).defaultValue(new LinkedHashSet<>((Collection<Block>) def)).build(),
				v -> new ArrayList<>((Collection<?>) v), false);
		}
		if (element == Item.class) {
			return new Link(info.name(), group.items(title).description(about).defaultValue(new LinkedHashSet<>((Collection<Item>) def)).build(),
				v -> new ArrayList<>((Collection<?>) v), false);
		}
		// Anything else (large numbers, block maps, coordinates, rotations) as the text Baritone's #set takes.
		String text = Baritone.settingDefaultText(info.name());
		if (text == null) return null;
		return new Link(info.name(), group.string(title).description(about + " Written as Baritone's #set takes it.").defaultValue(text).build(), v -> v, true);
	}

	/** The element type of a {@code List<X>}, or null. */
	private static Class<?> listOf(Type type) {
		if (type instanceof ParameterizedType p && p.getRawType() == List.class && p.getActualTypeArguments()[0] instanceof Class<?> c) return c;
		return null;
	}

	@Override
	protected void onEnable() {
		for (Link link : links) apply(link);
		render();
	}

	@Override
	protected void onDisable() {
		for (Link link : links) restore(link);
		render();
	}

	/** Draws Baritone's way Myriad's while the module and Custom Render are both on. */
	private void render() {
		boolean on = isEnabled() && custom.get();
		if (on == rendering) return;
		rendering = on;
		if (on) {
			renderer.start();
			Myriad.events().subscribe(renderer);
		} else {
			Myriad.events().unsubscribe(renderer);
			renderer.stop();
		}
	}

	/** Sets {@code link}'s value in Baritone if it differs from Baritone's default; back to Baritone's own if it doesn't. */
	private void apply(Link link) {
		Object value = link.setting().get();
		if (Objects.equals(value, link.setting().defaultValue())) {
			restore(link);
			return;
		}
		Object current = current(link);
		if (!before.containsKey(link.name())) before.put(link.name(), current);
		Object wanted = link.toBaritone().apply(value);
		boolean done = link.asText() ? Baritone.setSettingText(link.name(), (String) wanted) : Baritone.setSetting(link.name(), wanted) != null;
		if (done) set.put(link.name(), current(link));
	}

	/** Puts Baritone's own value back, unless something changed the setting since this set it. */
	private void restore(Link link) {
		if (!before.containsKey(link.name())) return;
		Object own = before.remove(link.name()), ours = set.remove(link.name());
		if (!Objects.equals(current(link), ours)) return;
		if (link.asText()) Baritone.setSettingText(link.name(), (String) own);
		else Baritone.setSetting(link.name(), own);
	}

	/** Baritone's value now: its text for text settings, a copy for lists (Baritone's own list may be changed in place). */
	private static Object current(Link link) {
		if (link.asText()) return Baritone.settingText(link.name());
		Object v = Baritone.getSetting(link.name());
		return v instanceof List<?> list ? new ArrayList<>(list) : v;
	}

	/**
	 * Custom Render: where Baritone is going drawn in place of Baritone's own lines and boxes (turned off while this
	 * draws, and back on after): its path as a smooth line with rounded corners and a gradient flowing along it, the
	 * blocks it will break and place on the way, and its goal as a ghost of you standing there: your skin, armour, cape
	 * or elytra and what you hold, faded, outlined in the render mode (seen through walls by default). The blocks are
	 * shown the same way. Read through Baritone's public API, so it follows any Baritone build; the blocks to break and
	 * place are worked out from the path (Baritone's own lists aren't public).
	 */
	private final class Renderer {

		/** How many nodes ahead the blocks to break and place are shown for. */
		private static final int BLOCKS_AHEAD = 40;

		private final SettingGroup sgPath = settings.group("Path");
		private final BoolSetting path = sgPath.bool("Path").description("Draw the path Baritone walks.").defaultValue(true).visible(custom::get).build();
		private final ColorSetting pathStart = sgPath.color("Start").description("One end of the gradient.")
			.defaultValue(SettingColor.accent()).visible(() -> custom.get() && path.get()).build();
		private final ColorSetting pathEnd = sgPath.color("End").description("The other end of the gradient.")
			.defaultValue(SettingColor.role(SettingColor.Mode.SECONDARY)).visible(() -> custom.get() && path.get()).build();
		private final DoubleSetting flow = sgPath.decimal("Flow").description("How fast the gradient flows along the path, in blocks a second (0 holds it still).")
			.defaultValue(6).range(0, 40).decimals(1).visible(() -> custom.get() && path.get()).build();
		private final DoubleSetting width = sgPath.decimal("Width").description("Line width, in pixels.")
			.defaultValue(2.5).range(0.5, 8).decimals(1).visible(() -> custom.get() && path.get()).build();
		private final IntSetting rounding = sgPath.integer("Rounding").description("How round the corners are: 0 draws Baritone's straight segments.")
			.defaultValue(3).range(0, 5).visible(() -> custom.get() && path.get()).build();
		private final BoolSetting pathThroughWalls = sgPath.bool("Through Walls").description("Draw the path over blocks in the way.")
			.visible(() -> custom.get() && path.get()).build();

		private final SettingGroup sgBlocks = settings.group("Blocks");
		private final BoolSetting blocks = sgBlocks.bool("Blocks").description("Mark the blocks Baritone will break and place along the path ahead.")
			.defaultValue(true).visible(custom::get).build();
		private final ColorSetting breakColor = sgBlocks.color("Break").description("Blocks in the way it will mine.")
			.defaultValue(SettingColor.role(SettingColor.Mode.RED)).visible(() -> custom.get() && blocks.get()).build();
		private final ColorSetting placeColor = sgBlocks.color("Place").description("Gaps it will fill to walk over (bridging, pillaring).")
			.defaultValue(SettingColor.role(SettingColor.Mode.BLUE)).visible(() -> custom.get() && blocks.get()).build();
		private final RenderSettings blockStyle = RenderSettings.builder(sgBlocks).visible(() -> custom.get() && blocks.get()).throughWalls(false).build();

		private final SettingGroup sgGoal = settings.group("Goal");
		private final EnumSetting<GoalLook> goal = sgGoal.oneOf("Goal", GoalLook.GHOST)
			.description("Ghost: you, standing at the goal. Box: the goal block. None: nothing.").visible(custom::get).build();
		private final DoubleSetting opacity = sgGoal.decimal("Opacity").description("How solid the ghost is.")
			.defaultValue(0.35).range(0.05, 1).decimals(2).visible(() -> custom.get() && goal.get() == GoalLook.GHOST).build();
		private final ColorSetting goalColor = sgGoal.color("Highlight").description("The ghost's (or the box's) highlight.")
			.defaultValue(SettingColor.accent()).visible(() -> custom.get() && goal.get() != GoalLook.NONE).build();
		private final BoolSetting bob = sgGoal.bool("Float").description("The ghost hovers a little, bobbing, and its highlight pulses.").defaultValue(true)
			.visible(() -> custom.get() && goal.get() == GoalLook.GHOST).build();
		private final RenderSettings goalStyle = RenderSettings.builder(sgGoal).visible(() -> custom.get() && goal.get() != GoalLook.NONE).build();

		/** The path's points this tick, rounded; and the blocks ahead to break and to place. */
		private final TickCached<List<Vec3>> points = TickCached.of(this::roundedPath);
		private final TickCached<List<BlockPos>[]> work = TickCached.of(this::blocksAhead);
		/** The way the ghost faces now (turned smoothly towards where it should), or NaN before the first frame. */
		private float ghostYaw = Float.NaN;
		/** Baritone's own drawing as it was before this turned it off (null: not changed). */
		private Object renderPathBefore, renderGoalBefore, renderBoxesBefore;



		/** Turns Baritone's own drawing off while this draws. */
		void start() {
			renderPathBefore = Baritone.setSetting("renderPath", false);
			renderGoalBefore = Baritone.setSetting("renderGoal", false);
			renderBoxesBefore = Baritone.setSetting("renderSelectionBoxes", false);
		}

		void stop() {
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

		/** The blocks to break and place, and a Box goal, in the render mode. */
		@Subscribe(inGame = true)
		private void onShow(RenderEvent e) {
			if (blocks.get()) {
				List<BlockPos>[] w = work.get();
				for (BlockPos p : w[0]) e.block(p, blockStyle, breakColor.get().argb());
				for (BlockPos p : w[1]) e.box(new AABB(p), blockStyle, placeColor.get().argb());
			}
			if (goal.get() == GoalLook.BOX) {
				for (Baritone.GoalSpot spot : Baritone.goalSpots()) {
					int y = spot.column() ? (int) columnFloor(spot.x(), spot.z()) : spot.y();
					e.box(new AABB(new BlockPos(spot.x(), y, spot.z())), goalStyle, goalColor.get().argb());
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
			// Outlined by the shader in Shader mode; in Box mode, a box around it.
			boolean shader = RenderMode.current() == RenderMode.SHADER;
			Ghosts.submit(e, state, at, opacity.getFloat(), shader ? goalStyle.style() : null, color);
			if (!shader) goalStyle.box(e.shapes(), mc.player.getDimensions(Pose.STANDING).makeBoundingBox(at), color);
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
}
