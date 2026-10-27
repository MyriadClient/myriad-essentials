package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Target;
import dev.myriadclient.api.elytra.ChestSwap;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobContext;
import dev.myriadclient.api.job.Progress;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.util.Baritone;
import dev.myriadclient.api.util.Interactions;
import dev.myriadclient.api.util.Packets;
import dev.myriadclient.essentials.modules.player.AutoEat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Bounces along a highway along the ground, on an elytra. It jumps each time you touch down and keeps you on the nearest
 * 45° lane. The client keeps gliding through each ground touch instead of letting the elytra close; the server still
 * closes it on landing, so it's reopened (one packet) once you're back in the air, but your own physics never drop to
 * walking while that round trip happens. Jump is only pressed on the ticks you're on the ground and forward is never
 * pressed (strict anticheats check both against the glide); sprint stays on so every jump still adds its boost, and you
 * can use your inventory while it bounces (clicks go through sprinting; where Grim also judges held keys, sprint lets go
 * for a tick in the air for them). The pitch dives while
 * you're rising and levels out as you fall, which turns each bounce into the most forward speed; it only goes to the
 * server and the flight physics, so you can look around freely. The server stops the glide at each landing; the answers
 * to Grim's pings from then on are held back until you're in the air again (see GlideHold), so Grim keeps expecting the
 * glide the client keeps up, and the elytra is opened again (with a jump press, as vanilla does) right after they're
 * sent. The server's glide restarts every hop, so on vanilla servers the elytra doesn't wear. 2b2t counts every tick the
 * server glides you, however short each glide: with No Durability the chestplate stays on through each hop and the
 * elytra only goes on for the moment of the start, so the server barely glides at all (paused while you eat).
 * <p>
 * The highway is fixed when you turn it on (where you face, rounded as Elytra Fly's Snap says: the nearest 45° by
 * default; through the middle of the block you stand on), so looking around never steers; drifting off that line turns the flight a little back onto it. Far enough ahead
 * to stop in time (it grows with your speed) it watches for anything in the way: a block (an ender chest, a portal, a
 * wall), chunks that haven't loaded, and where the hops ahead come down, a hole (two blocks deep) or a ceiling low
 * enough for the jump to hit; one the hops pass over isn't in the way. It then stops jumping and lands, as you would, and
 * Obstacles decides what happens next. Clear hands over to Baritone, with a goal at the first clear spot past it, which
 * gets you there however it can (walking round, mining, placing, never through a nether portal: standing in one, you're
 * walked out of it first); once you're there you face along the lane again and
 * bouncing goes on, and if Baritone can't get there the lane counts as blocked. Without Baritone, Clear mines through
 * blocks and fills holes itself, placing blocks only at floor level, never above your feet. Stop stops there. It also
 * notices when it's stuck, or the server keeps setting you back, and doesn't keep trying. The lane's height is kept from
 * where you first stand: off the line or below it (a fall off the highway), Baritone walks you back onto it before
 * bouncing on. When the way can't be cleared, If Blocked waits there or disconnects.
 * <p>
 * Ground Terrain is for land that isn't flat: it steers where you look, with no lane or lane height, and follows the
 * ground. A step up of up to a block is hopped up, the hop rising without its dive until you're over the step's top
 * and timed (a tick held back where needed) so it reaches the face high enough; a drop of up to two is glided down.
 * Anything higher, something in the way at head height, water or lava, or a deeper drop stops it, landing in front:
 * it clears nothing there, and bounces on once you look somewhere clear.
 * <p>
 * For addon developers: each tick of a bounce goes through the {@link #HOP} hook, which can change the pitch it flies,
 * whether it jumps and when the glide starts; {@link #PASS_THROUGH} says which blocks don't count as in its way.
 *
 * <pre>{@code
 * ctx.hook(Recast.HOP)
 *     .named("Steady pitch")
 *     .handle(call -> call.input().withPitch(40));
 * }</pre>
 */
public class Recast extends GlideMode {
	/**
	 * One tick of a bounce, for {@link #HOP}: on the ground (for how many ticks) or in the air (for how many), the
	 * floor's height, the way the lane runs, whether it follows terrain; and what Recast does with it: the pitch it flies,
	 * whether it jumps (on the ground), and whether each glide starts on the hop's last tick in the air, swapping nothing,
	 * instead of as it rises (with No Durability's chestplate swaps).
	 */
	public record Hop(boolean onGround, int groundTicks, int airTicks, double floorY, Vec3 lane, boolean terrain, float pitch,
					  boolean jump, boolean lateStart) {
		public Hop withPitch(float pitch) {
			return new Hop(onGround, groundTicks, airTicks, floorY, lane, terrain, pitch, jump, lateStart);
		}

		public Hop withJump(boolean jump) {
			return new Hop(onGround, groundTicks, airTicks, floorY, lane, terrain, pitch, jump, lateStart);
		}

		public Hop withLateStart(boolean lateStart) {
			return new Hop(onGround, groundTicks, airTicks, floorY, lane, terrain, pitch, jump, lateStart);
		}
	}

	/** How Recast flies a tick of its bounce: the input is its own plan, the caller is Recast. */
	public static final Hook<Hop, Hop> HOP = Hook.of("myriad-essentials:recast_hop", "Recast hop",
		"How Recast flies each tick of a bounce: its pitch, whether it jumps, when the glide starts.");

	/** Whether Recast looks through the block at a position when it checks the way ahead (default: no). */
	public static final Hook<BlockPos, Boolean> PASS_THROUGH = Hook.of("myriad-essentials:recast_pass_through", "Recast pass-through",
		"Blocks Recast doesn't count as in its way.");

	public enum Obstacles {
		CLEAR, STOP
	}

	public enum IfBlocked {
		WAIT, DISCONNECT
	}

	public enum Ground {
		HIGHWAY, TERRAIN
	}

	private final EnumSetting<Ground> ground = sgGeneral.oneOf("Ground", Ground.HIGHWAY)
		.description("Highway: a lane fixed as you turn it on, kept at its height, stopping at any block or hole in it to clear it. Terrain: steers where you look and follows the ground, hopping up steps of a block, stopping at anything higher, water, or a drop of more than two.")
		.visible(this::chosen).build();
	private final EnumSetting<Obstacles> obstacles = sgGeneral.oneOf("Obstacles", Obstacles.CLEAR)
		.description("Clear: Baritone takes you past (walking round, mining or placing), or without it, mines through blocks and fills holes at floor level. Stop: stop at them.")
		.visible(() -> chosen() && !terrain()).build();
	private final EnumSetting<IfBlocked> ifBlocked = sgGeneral.oneOf("If Blocked", IfBlocked.WAIT)
		.description("When the way can't be cleared (with Stop, anything in the lane): wait there, or disconnect.").visible(() -> chosen() && !terrain()).build();

	private enum State {
		IDLE, BOUNCING, BRAKING, WALKING, MINING, PATHING, WAITING, BLOCKED, UNDER, FILLING, HUNGRY
	}

	/** What's ahead in the lane, and how far along it. CEILING: too low to hop under, but room to walk. */
	private enum Hazard {
		BLOCKS, CEILING, HOLE, UNLOADED, STUCK, OFF_LANE
	}

	private record Obstruction(Hazard hazard, double distance, List<BlockPos> blocks) {
	}

	/** On terrain: the first step up ahead, how far along and how far above the floor its top is. */
	private record Rise(double distance, double height) {
	}

	/** Dive while vertical speed is above this, level out below it. */
	private static final double DIVE_UNTIL_Y = -0.2;
	/**
	 * How far ahead the lane is watched, in blocks, plus this many ticks of travel at the current speed. A hop
	 * is about 9 ticks in the air, and stopping takes the rest of the hop and a few ticks of sliding once you land.
	 */
	private static final double LOOK_AHEAD = 4, LOOK_AHEAD_TICKS = 14;
	/**
	 * Once stopped, bouncing only starts again with this much clear: what it watches at a usual bounce's speed
	 * (2 blocks a tick), so it doesn't hop, see the obstacle again and stop, over and over, on the way up to it.
	 */
	private static final double RESUME_LOOK_AHEAD = LOOK_AHEAD + 2 * LOOK_AHEAD_TICKS;
	/** How far apart the swept hitboxes are along the lane (each also covers the step to the next). */
	private static final double SWEEP_STEP = 0.5;
	/** What counts as in the way above the floor starts this high: carpets and snow layers don't. */
	private static final double FLOOR_CLEARANCE = 0.2;
	/**
	 * A standing player's height, and the top of a hop: you rise standing for the jump's tick and the next two, until the
	 * glide starts again (to 0.75 up, a head at 2.55; the climb is the same at any speed), then glide 0.6 tall,
	 * well below a standing head. So a ceiling between the two can be walked under, and only gets in the way of the
	 * start of a hop: from where it takes off, for {@link #RISE_TICKS} of travel.
	 */
	private static final double STAND_HEIGHT = 1.8, JUMP_TOP = 2.6;
	private static final int RISE_TICKS = 3;
	/**
	 * Where the hops ahead come down is known to within this much, and they come down up to this share of how far ahead
	 * it is further on (about 2% in tests: the hops' speed grows a little faster than counted).
	 */
	private static final double TOUCHDOWN_MARGIN = 0.5, TOUCHDOWN_ERROR = 0.04;
	/** Your speed through the air keeps this much of itself each tick. */
	private static final double GLIDE_DRAG = 0.99;
	/** A jump's vertical speed. */
	private static final double JUMP_POWER = 0.42;
	/** How far to look for a way out of a nether portal you stand in, in blocks. */
	private static final double PORTAL_EXIT = 3;
	/**
	 * Stopped, and setting off again, where the hops will come down isn't known (the first are short, and how short
	 * isn't known well): within this many blocks they can come down, and rise, anywhere; past it, nothing counts yet.
	 */
	private static final double START_SPAN = 16;
	/** Past a low ceiling, how much further a wall or a hole still counts as the thing to deal with first. */
	private static final double UNDER_LOOK_AHEAD = 4;
	/** Ticks to stop after the server snaps you back; and how many snaps within the window mean it's stuck. */
	private static final int FLAG_PAUSE = 5, SETBACK_LIMIT = 3, SETBACK_WINDOW = 100;
	/** Less than this far along the lane in this many ticks of trying means stuck. */
	private static final double STUCK_PROGRESS = 1.5;
	private static final int STUCK_TICKS = 40;
	/**
	 * keeping to the line: the sideways speed asked for per block off it (blocks a tick), its most, how hard it
	 * damps the sideways speed you have, and the most the flight turns away from the lane, in degrees.
	 */
	private static final double STEER_GAIN = 0.15, STEER_MAX_SPEED = 0.25, STEER_DAMPING = 1.5;
	private static final float STEER_MAX_TURN = 10;
	/** With Baritone: this far off the line, walk back to it; and how far past an obstacle to look for room. */
	private static final double RECENTRE_DISTANCE = 3, PASS_SEARCH = 48;
	/** This far below the lane's height, standing, you've come off the highway. */
	private static final double LANE_DROP = 1.5;
	/** filling a hole: placing within this reach, and looking this far past the hole's edge for more of it. */
	private static final double PLACE_REACH = 4, HOLE_SPAN = 5;
	/** Within this of a block, mine it rather than walk closer. */
	private static final double MINE_REACH = 3.5;
	/**
	 * On terrain: the highest step hopped up (a block), the deepest drop bounced off (more would cost a fall), and the
	 * lowest rise that needs a hop at all (walking steps up anything lower).
	 */
	private static final double STEP_MAX = 1.05, DROP_MAX = 2, STEP_ASSIST = 0.6;
	/**
	 * On terrain: how far over a step's top the climb rises before diving again; an ordinary hop's length and the ticks
	 * a climbing hop stays over a block's height, about; and what a sprint jump adds to your speed, and a hop over it.
	 */
	private static final double CLIMB_MARGIN = 0.15, JUMP_PUSH = 0.2, HOP_GAIN = 0.19;
	private static final int HOP_TICKS = 10, CLIMB_TICKS = 11;
	/** On terrain, once stopped, how much clear ahead (where you look) starts the bounce again. */
	private static final double TERRAIN_RESUME = LOOK_AHEAD + 8;

	private State state = State.IDLE;
	/** On terrain: the step up ahead (see {@link #scanTerrain}), and how far you've come, for telling when you're stuck. */
	private @Nullable Rise rise;
	private double travelled;
	private @Nullable Vec3 lastPos;

	private boolean wantJump, wantForward, wantSneak;
	/** The lane's height, where you first stood (NaN until then). */
	private double laneY = Double.NaN;
	/** Baritone found no way past this obstacle; mine or fill instead until bouncing goes on. */
	private boolean baritoneFailed;
	private int pauseTicks;
	private double groundY;
	/** Ticks since you last touched the ground, and on the ground since you landed. */
	private int airTicks, groundTicks;
	/** The last hop's ticks in the air and on the ground (the jump's tick the last of them): the ones ahead are the same. */
	private int hopAir = HOP_TICKS - 1, hopGround = 1;
	/** The pitch this tick of the bounce was flown at. */
	private float flownPitch;
	/**
	 * Recast with No Durability: the chestplate is worn through each hop, the elytra going on only for the moment of
	 * each glide start (see {@link #bounce}).
	 */
	private boolean chestMode;
	/**
	 * chestplate on: a start has gone out since you last touched the ground; and ticks since it did. One a hop
	 * is enough (the glide hold covers the rest of it); a hop that runs long (off an edge) gets another every
	 * {@link #RESTART_TICKS}, as No Durability's swapping does, well inside the hold's second.
	 */
	private boolean hopStarted;
	private int sinceStart;
	private static final int RESTART_TICKS = 8;
	/**
	 * Ticks since the server's glide last (re)started, counted until the server says it stopped, landings
	 * included: it can miss one (the landing and the next hop's packets handled in the same server tick, with jitter or
	 * low TPS) and glide on through the next hop. The chestplate ends it at {@link #LONG_GLIDE}, room for jitter and lag
	 * short of the 20 that wear the elytra.
	 */
	private int longGlide;
	private static final int LONG_GLIDE = 10;
	/** A long glide is only cut this close to the ground, so dropping out of it can't cost a fall. */
	private static final double CUT_HEIGHT = 2.5;
	/** The lane's direction (where you faced, snapped; NaN until fixed) and a point on its line. */
	private float lane = Float.NaN;
	private Vec3 lineOrigin;
	private BlockPos mining;
	private int portalWait;
	/** With Baritone: the job taking you past an obstacle (Elytra Fly's, shown in the Jobs window), while it runs. */
	private @Nullable Job<Void> handoff;
	/** With Baritone: your pitch when it took over (to look that way again after), and its settings before. */
	private float handoffPitch;
	private Object breakBefore, placeBefore, throwawayBefore, avoidBefore;
	/** With Baritone: ticks without getting closer, and without Baritone going, that mean it can't get there. */
	private static final int PATH_STALL = 300, PATH_START = 40;
	/** How far along the lane you were each of the last {@link #STUCK_TICKS} ticks spent trying to move. */
	private final double[] progress = new double[STUCK_TICKS];
	private int progressCount;
	/** Game times of recent setbacks. */
	private final ArrayDeque<Long> setbacks = new ArrayDeque<>();
	/** Stuck, or set back too often: the lane counts as blocked until it's passed. */
	private boolean forcedBlock;
	private boolean warned;
	/** Ticks spent waiting for the chestplate before bouncing, and the most. */
	private int readyWait;
	private static final int READY_WAIT = 40;

	Recast(ElytraFly fly) {
		super(fly, ElytraFly.RECAST);
	}

	@Override
	protected void resetFlight() {
		Job<Void> h = handoff;
		handoff = null;
		// Its cancel stops Baritone, and puts Baritone's settings back as it ends.
		if (h != null) h.cancel();
		state = State.IDLE;
		groundY = Double.NaN;
		wantJump = wantForward = wantSneak = forcedBlock = warned = baritoneFailed = false;
		laneY = Double.NaN;
		letGo();
		putElytraBack();
		pauseTicks = progressCount = sinceStart = 0;
		hopAir = HOP_TICKS - 1;
		hopGround = 1;
		hopStarted = false;
		lane = Float.NaN;
		rise = null;
		lastPos = null;
		travelled = 0;
		setbacks.clear();
		stopMining();
	}

	@Override
	public String hudInfo() {
		return switch (state) {
			case BRAKING -> "Stopping";
			case WALKING, MINING -> "Mining";
			case FILLING -> "Filling";
			case UNDER -> "Low ceiling";
			case PATHING -> "Baritone";
			case WAITING -> "Loading";
			case BLOCKED -> "Blocked";
			case HUNGRY -> "Hungry";
			case BOUNCING -> pauseTicks > 0 ? "Flagged" : terrain() ? "Terrain" : String.format("%.0f°", lane);
			case IDLE -> null;
		};
	}

	// ---- what the mixins and other modules ask (through ElytraFlight) ---------------------------------------------

	/** Whether it's bouncing: it lets the glide drop and opens it again itself, every hop. */
	/** The bounce lets the glide drop and opens it again itself: No Durability's catch keeps out of it. */
	@Override
	public boolean letsGlideDrop() {
		return bouncing();
	}

	boolean bouncing() {
		return state != State.IDLE;
	}

	/**
	 * Keep sprinting while gliding, so each jump off the ground adds the sprint boost without holding forward: when you
	 * could sprint at all (enough food, not blind), as Grim checks that. Clicks go through sprinting (core stops it for
	 * the server around them in the same tick); except in the air while a click waits for a still tick (where Grim also
	 * judges the keys you hold, see Inventory.clickWaiting): gliding, sprint changes nothing, so it's let go of with
	 * the keys and the click goes on the next tick, mid-hop.
	 */
	boolean holdsSprint() {
		var p = mc.player;
		return holdGlide && !yieldSprint && p != null && p.getFoodData().hasEnoughFood() && !p.hasEffect(MobEffects.BLINDNESS);
	}

	/** In the air with a click waiting, sprint is let go of for it (see {@link #holdsSprint}). */
	private boolean yieldSprint;

	/** Whether it has you on foot for now (walking round or clearing something, waiting): no gliding meanwhile. */
	boolean onFoot() {
		return switch (state) {
			case PATHING, WALKING, MINING, FILLING, UNDER, BLOCKED, WAITING, HUNGRY -> true;
			case IDLE, BOUNCING, BRAKING -> false;
		};
	}

	/**
	 * Whether the bounce may swap the elytra with a chestplate (Auto Armor leaves the chest slot alone meanwhile): with
	 * No Durability, all the while it's going, stops for obstacles included.
	 */
	@Override
	protected boolean swapsChest() {
		return chestSwaps() && bouncing();
	}

	// ---- tick -----------------------------------------------------------------------------------------------------

	@Override
	protected void input(InputEvent e) {
		if (wantJump) e.jump = true;
		if (wantForward) {
			e.forward = e.sprint = true;
			e.backward = e.left = e.right = false;
		}
		if (wantSneak) {
			// Up to a hole's edge: sneaking keeps you from walking off it.
			e.sneak = true;
			e.sprint = false;
		}
	}

	@Override
	protected void inputDone(InputEvent e) {
		// The bounce's jumps can't wait for a click (see holdsSprint): it gets its still tick in the air instead.
		if (e.jump && (wantJump || wantOpen) && state == State.BOUNCING) Myriad.inventory().keepKeys();
	}

	@Override
	protected void tickFlight() {
		wantJump = wantForward = wantSneak = yieldSprint = false;
		if (flagged) {
			flagged = false;
			pauseTicks = FLAG_PAUSE;
			countSetback();
		}
		if (mc.player.onGround() && inNetherPortal(mc.player.getBoundingBox())) {
			leavePortal();
			return;
		}
		if (state == State.PATHING) {
			tickPathing();
			return;
		}
		if (!canGlide()) {
			stopBouncing();
			state = State.IDLE;
			return;
		}
		if (Float.isNaN(lane)) fixLine();
		// Too hungry to sprint: the bounce's push forward is the sprint jump's, so it would only hop on the spot (and take
		// that for stuck). It stops, says so, and goes on once you've eaten; eating, it bounces on meanwhile.
		if (tooHungry()) {
			if ((state == State.BOUNCING || state == State.BRAKING) && !brake()) {
				state = State.BRAKING;
				return;
			}
			stand();
			if (state != State.HUNGRY) status("Too hungry to sprint: bouncing goes on once you've eaten (Auto Eat does it for you)", true);
			state = State.HUNGRY;
			return;
		}
		if (state == State.HUNGRY) state = State.IDLE;
		if (mc.player.onGround()) {
			groundY = mc.player.getY();
			if (Double.isNaN(laneY)) laneY = groundY;
		}
		Vec3 pos = mc.player.position();
		if (lastPos != null) travelled += Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z);
		lastPos = pos;

		double range = state == State.BOUNCING ? lookAhead() : Math.max(lookAhead(), terrain() ? TERRAIN_RESUME : RESUME_LOOK_AHEAD);
		Obstruction ahead;
		if (forcedBlock) ahead = new Obstruction(Hazard.STUCK, 0, List.of());
		// Well off the highway or below it (a setback, the way round something, a fall): back onto it first.
		else if (!terrain() && mc.player.onGround() && offLane()) ahead = new Obstruction(Hazard.OFF_LANE, 0, List.of());
		else ahead = terrain() ? scanTerrain(range) : scanAhead(range);
		if (ahead != null) {
			handleObstacle(ahead);
			return;
		}
		// A stop once begun is finished before bouncing goes on: braking's dive isn't a hop, so where the hops would come
		// down isn't known again until you stand.
		if (state == State.BRAKING && !brake()) return;
		if (state != State.BOUNCING && !readyToBounce()) return;
		if (state != State.BOUNCING) {
			stopMining();
			Myriad.placement().cancel(this);
			state = State.BOUNCING;
			warned = baritoneFailed = false;
			progressCount = 0;
		}
		if (pauseTicks > 0) {
			// The server rejected a move: let its correction land before bouncing on from there. A glide the server
			// started before it still gets cut short meanwhile.
			pauseTicks--;
			spoofing = holdGlide = false;
			letGo();
			cutLongGlide(elytraOpen(), false);
			progressCount = 0;
			return;
		}
		if (trackStuck()) return;
		if (holdBackForStep()) {
			// No jump this tick: the glide let go, you slide a little slower and a little nearer (see holdBackForStep).
			spoofing = holdGlide = false;
			letGo();
			return;
		}
		bounce();
	}

	/**
	 * Before bouncing (on), or again after a stop: with No Durability, the chestplate is brought into the hotbar first,
	 * while you stand, since a click mid-bounce has to wait for a still moment it may not get. Only while one is on its
	 * way, and not for long.
	 */
	private boolean readyToBounce() {
		if (!chestSwaps() || !mc.player.onGround() || Myriad.elytra().chestSwap().ensurePair(true) != ChestSwap.Need.WAITING || ++readyWait > READY_WAIT) {
			readyWait = 0;
			return true;
		}
		stand();
		state = State.WAITING;
		return false;
	}

	/** One tick of the bounce itself. */
	private void bounce() {
		Myriad.elytra().glideHold().arm(this);
		// Vanilla waits 10 ticks between held jumps; jump the tick you touch down.
		Interactions.setJumpCooldown(0);
		boolean ground = mc.player.onGround();
		if (ground && airTicks > 0) hopAir = airTicks;
		if (!ground && groundTicks > 0) hopGround = groundTicks;
		airTicks = ground ? 0 : airTicks + 1;
		groundTicks = ground ? groundTicks + 1 : 0;
		yieldSprint = !ground && Myriad.inventory().clickWaiting();
		sinceStart++;
		if (ground) {
			hopStarted = false;
			groundY = mc.player.getY();
		}
		Hop hop = hop(ground);
		flownPitch = hop.pitch();
		spoof(steeredYaw(), hop.pitch());
		if (ground) wantJump = hop.jump();

		boolean gliding = elytraOpen();
		if (gliding) holdGlide = true;
		// With No Durability the chestplate is worn through each hop: 2b2t wears the elytra by every tick
		// the server glides you, a stop doesn't reset its count (vanilla needs 20 ticks of one glide), so the server
		// should glide as little as it can. Each start puts the elytra on, starts the glide and puts the chestplate back
		// on in the same tick: the server glides for a tick at most, and the glide hold keeps you (and Grim) gliding
		// through the rest. No chestplate in the hotbar or off hand: one is brought in from the inventory.
		// Not while you eat (or use anything held): Grim stops it at every swap. And then no glides at all: the hops go
		// on without (a little slower) until you're done. With the elytra on, each would last the whole hop, and eating
		// can go on for several items. Nothing is swapped meanwhile, so eating can start whenever (Auto Eat, or right
		// click), the chestplate on or not; between Auto Eat's bites too, or a swap would stop the next one.
		boolean eating = chestSwaps() && (mc.player.isUsingItem() || AutoEat.wantsToEat());
		// A late start (see Hop) goes on the last tick in the air: the server glides from it to the landing, a tick at
		// most, with nothing swapped, so eating needn't stop it.
		boolean late = hop.lateStart();
		chestMode = !late && chestSwaps() && Myriad.elytra().chestSwap().ready() && !eating;
		boolean mayGlide = late || !eating;
		if (chestSwaps() && !late) noDur.chestplateAtHand(false);
		boolean cleared = Myriad.elytra().glideHold().cleared(this);
		// What's worn only counts once the server has answered the last swap (see Myriad.elytra().chestSwap().settled): with jitter the
		// client can show a moment that's already past, and swapping on that puts the client and Grim out of step.
		boolean settled = !chestMode || Myriad.elytra().chestSwap().settled();
		boolean startDue = late ? landsThisTick(hop.pitch()) : !chestMode || !hopStarted || sinceStart >= RESTART_TICKS;
		// Grim already knows the server stopped the glide (its ping got answered before the hold could take it, see
		// Myriad.elytra().glideHold().exposed): start again at once, or it sets you back for gliding on. Without waiting for the last swap
		// to settle: the stop is the server's answer to it.
		boolean exposed = Myriad.elytra().glideHold().exposed(this);
		// Where nothing holds the stop back from the anti-cheat (6b6t), only once the server has answered the last start:
		// a stop older than it would have this start end the glide that one began. A late start waits for it everywhere:
		// the server's glide of a tick says it glides and then that it stopped a tick apart, and a start between the two
		// would be a second start to Grim.
		boolean answered = !late && AntiCheat.inEffect().simulatesGlide() || Myriad.elytra().glideHold().stoppedSinceStart(this);
		if (cleared && answered && !ground && airTicks >= 2 && (exposed || settled && startDue)) {
			// The server stopped the glide (at the landing, or when the chestplate went on) and the client kept it up:
			// start it again now you're back in the air, the held ping answers first (on the tick jump goes down) so
			// Grim sees the stop, then the start.
			if (!jumpedLastTick) {
				Myriad.elytra().glideHold().release(this);
				if (chestMode && Myriad.elytra().chestSwap().startGlide()) {
					// The start (the elytra on first, if it's off) is sent here; the chestplate goes straight back on.
					Myriad.elytra().chestSwap().swap();
					hopStarted = true;
					sinceStart = 0;
				} else {
					// Not gliding for vanilla's check this tick, so its jump press opens the elytra (and sends the start).
					holdGlide = false;
					mc.player.stopFallFlying();
				}
			}
			if (mayGlide) startGliding();
		} else if (!gliding && !ground && !cleared && mayGlide && (!late || landsThisTick(hop.pitch()))) {
			// Take off the same way, after the first jump or off a ledge. With the chestplate on through each hop, as a
			// restart: the elytra on, the start and the chestplate back on in one tick, the client (and Grim) gliding on
			// with the hold. Left to vanilla (the elytra put on, its jump press starting the glide), the server would
			// glide you to the landing: the first hop, and the first after you eat, a Baritone hand-over or a setback.
			if (chestMode && airTicks >= 2 && settled && !jumpedLastTick && Myriad.elytra().chestSwap().startGlide()) {
				Myriad.elytra().chestSwap().swap();
				// The client glides from this start too, as vanilla's would have it (or the next tick takes off again:
				// a second start while Grim has you gliding, its ElytraA).
				mc.player.startFallFlying();
				holdGlide = true;
				hopStarted = true;
				sinceStart = 0;
			}
			startGliding();
		} else {
			cutLongGlide(gliding, cleared);
		}
	}

	/** This tick of the bounce: Recast's own, unless a {@link #HOP} hook flies it differently. */
	private Hop hop(boolean ground) {
		float pitch = mc.player.getDeltaMovement().y > DIVE_UNTIL_Y && !climbing() ? 90 : 4;
		Hop asked = new Hop(ground, groundTicks, airTicks, groundY, laneDir(), terrain(), pitch, true, false);
		if (!HOP.hooked()) return asked;
		Hop hooked = HOP.call(this, asked, h -> h);
		return hooked != null ? hooked : asked;
	}

	/** Whether this tick's move lands, by the glide's own maths at {@code pitch}. */
	private boolean landsThisTick(float pitch) {
		var p = mc.player;
		double cos = Math.cos(Math.toRadians(pitch)), lift = cos * cos;
		double vy = p.getDeltaMovement().y + p.getGravity() * (-1 + lift * 0.75);
		if (vy < 0) vy += vy * -0.1 * lift;
		return p.getY() + vy * 0.98 <= groundY + 1e-4;
	}

	/**
	 * Counts the server's glide since it started (see {@link #longGlide}) and, with No Durability, ends it with the
	 * chestplate once it runs long: only once the server has answered the last swap, and with the ground close below.
	 */
	private void cutLongGlide(boolean gliding, boolean cleared) {
		var p = mc.player;
		longGlide = cleared || !gliding || !Myriad.elytra().chestSwap().elytraWorn() ? 0 : longGlide + 1;
		if (!chestMode || longGlide < LONG_GLIDE || !Myriad.elytra().chestSwap().settled()) return;
		if (mc.level.noBlockCollision(p, p.getBoundingBox().expandTowards(0, -CUT_HEIGHT, 0))) return;
		Myriad.elytra().chestSwap().swap();
		longGlide = 0;
	}

	/**
	 * Something's in the way (or the lane ahead hasn't loaded): stop the bounce first, then deal with it once you're
	 * standing.
	 */
	private void handleObstacle(Obstruction ahead) {
		if (state == State.BOUNCING || state == State.BRAKING) {
			if (!brake()) {
				state = State.BRAKING;
				return;
			}
		}
		if (terrain()) {
			// On terrain it only stops: you look another way, and it bounces on once that's clear.
			stand();
			forcedBlock = false;
			state = ahead.hazard() == Hazard.UNLOADED ? State.WAITING : State.BLOCKED;
			if (state == State.BLOCKED && !warned) {
				status((ahead.hazard() == Hazard.HOLE ? "A drop or water ahead" : "Something in the way") + ": stopped, look another way to go on", true);
			}
			warned = state == State.BLOCKED;
			return;
		}
		boolean clear = obstacles.get() == Obstacles.CLEAR;
		// Baritone takes you past when it's installed; mining and filling here only without it.
		boolean baritone = clear && Baritone.isAvailable();
		if (ahead.hazard() != Hazard.HOLE) Myriad.placement().cancel(this);
		switch (ahead.hazard()) {
			case UNLOADED -> {
				// Not an obstacle: wait where you are until it loads, then bounce on.
				stand();
				state = State.WAITING;
			}
			case CEILING -> walkUnder();
			case BLOCKS -> {
				if (!clear) blocked("Lane blocked");
				else if (baritone) handOff(ahead.distance(), "Lane blocked");
				else {
					// Unbreakable blocks (bedrock, barriers) can only be walked round.
					BlockPos breakable = null;
					for (BlockPos pos : ahead.blocks()) {
						if (mc.level.getBlockState(pos).getDestroySpeed(mc.level, pos) >= 0) {
							breakable = pos;
							break;
						}
					}
					if (breakable != null) mineOrApproach(breakable);
					else blocked("Lane blocked by something unbreakable");
				}
			}
			case HOLE -> {
				if (!clear) blocked("Hole in the lane");
				else if (baritone) handOff(ahead.distance(), "Hole in the lane");
				else if (!fillHole(ahead)) blocked("Hole in the lane, and no blocks to fill it");
			}
			case STUCK -> {
				forcedBlock = false;
				if (baritone) handOff(1, "Stuck (or set back over and over)");
				else blocked("Stuck (or set back over and over)");
			}
			case OFF_LANE -> {
				if (baritone) handOff(0, "Off the highway");
				else blocked("Off the highway");
			}
		}
	}

	/**
	 * Stops bouncing the way a player would, so nothing about it looks odd to the server: no more jumps and the glide let
	 * go, diving (along the lane) to land soon, then sliding to a halt. True once you're standing still.
	 */
	private boolean brake() {
		holdGlide = false;
		letGo();
		putElytraBack();
		var p = mc.player;
		// Still flying while the elytra's open, the tick you land too: along the lane, not where the camera looks.
		if (!p.onGround() || elytraOpen()) {
			spoof(heading(), 90);
			return false;
		}
		spoofing = false;
		Vec3 v = p.getDeltaMovement();
		return v.x * v.x + v.z * v.z < 0.03 * 0.03;
	}

	/** Food at 6 or less (Minecraft's line for sprinting), and nothing eating or about to. */
	private boolean tooHungry() {
		var p = mc.player;
		return p.getFoodData().getFoodLevel() <= 6 && !p.getAbilities().mayfly && !p.isUsingItem() && !AutoEat.wantsToEat();
	}

	/** Standing in front of an obstacle: no flight, nothing pressed. */
	private void stand() {
		spoofing = holdGlide = false;
		letGo();
		putElytraBack();
	}

	private void blocked(String why) {
		stand();
		state = State.BLOCKED;
		if (ifBlocked.get() == IfBlocked.DISCONNECT) {
			leave(why);
			return;
		}
		if (!warned) status(why + ", waiting until it's clear", true);
		warned = true;
	}

	/**
	 * What Recast is doing about the lane (Baritone taking you past something, waiting at a block): one notification,
	 * each replacing the last (the same one again is counted on it), not a stack of them on a broken stretch.
	 */
	private void status(String message, boolean warning) {
		Myriad.notifications().toast(name(), message)
			.level(warning ? Notifications.Level.WARNING : Notifications.Level.INFO)
			.lasting(warning ? 4000 : 3000)
			.replacing("essentials:recast")
			.show();
	}

	/**
	 * Fills a hole in the lane, placing blocks only at floor level: walks up to its edge (sneaking, so you don't walk
	 * off it), then places the gaps under where you'd walk, nearest first, each against one already there. False when
	 * there are no blocks to place.
	 */
	private boolean fillHole(Obstruction hole) {
		int slot = fillerSlot();
		if (slot == -1) return false;
		stopMining();
		state = State.FILLING;
		progressCount = 0;
		if (slot < 0) {
			// Being brought into the hotbar.
			stand();
			return true;
		}
		// As if sneaking against a chest or other block that would open instead.
		Placement.Options options = Placement.Options.STRICT.withRotate(true).withSneak(true);
		Vec3 eye = mc.player.getEyePosition();
		for (BlockPos gap : holeGaps(hole.distance())) {
			if (Myriad.placement().isPending(gap)) {
				stand();
				return true;
			}
			if (eye.distanceTo(Vec3.atCenterOf(gap)) > PLACE_REACH) break;
			if (Myriad.placement().check(gap, options).ok()) {
				stand();
				// Standing still first: the placement aims, then clicks a tick later from where you were when it aimed.
				if (mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4) Myriad.placement().place(this, gap, slot, options);
				return true;
			}
		}
		// Nothing to place from here: closer, along the lane, sneaking up to the edge.
		spoof(steeredYaw(), 0);
		wantForward = wantSneak = true;
		return true;
	}

	/** The floor blocks missing under where you'd walk along the lane, from just before the hole on, nearest first. */
	private List<BlockPos> holeGaps(double distance) {
		Vec3 dir = laneDir();
		AABB box = mc.player.getBoundingBox();
		int floorBlock = Mth.floor(floorY()) - 1;
		AABB feet = new AABB(box.minX, floorBlock + 0.1, box.minZ, box.maxX, floorBlock + 0.9, box.maxZ);
		List<BlockPos> gaps = new ArrayList<>();
		for (double d = Math.max(0, distance - 1); d <= distance + HOLE_SPAN; d += SWEEP_STEP / 2) {
			AABB at = feet.move(dir.scale(d));
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(at.minX, at.minY, at.minZ), BlockPos.containing(at.maxX, at.maxY, at.maxZ))) {
				if (!gaps.contains(pos) && mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) gaps.add(pos.immutable());
			}
		}
		return gaps;
	}

	/** Ordinary building blocks, the kind holes are filled with. */
	private static boolean isFiller(ItemStack s) {
		return s.getItem() instanceof BlockItem && Target.solid().preference(s) >= 0;
	}

	/** A hotbar slot with ordinary building blocks: -1 if there are none at all, -2 while some are brought in. */
	private int fillerSlot() {
		var inv = Myriad.inventory();
		Predicate<ItemStack> filler = Recast::isFiller;
		int slot = inv.findInHotbar(filler);
		if (slot >= 0) return slot;
		int from = inv.findInInventory(filler);
		if (from < 0) return -1;
		inv.pullToHotbar(from, s -> false);
		return -2;
	}

	/**
	 * Under a ceiling too low to hop (a two-high tunnel, a roof over the lane), or on the way to one: walks along the
	 * lane, sprinting, without mining anything, until there's room overhead to bounce on from.
	 */
	private void walkUnder() {
		if (state == State.MINING) stopMining();
		state = State.UNDER;
		spoof(steeredYaw(), 0);
		wantForward = true;
		trackStuck();
	}

	/**
	 * Standing in a nether portal, however you got there: out of it before it takes you to another dimension (it takes
	 * four seconds), the shortest way along the lane or across it to where you stand clear of it. Baritone, if it's
	 * taking you somewhere, is stopped first; once you're out, what's ahead is dealt with as usual.
	 */
	private void leavePortal() {
		Job<Void> h = handoff;
		handoff = null;
		if (h != null) h.cancel();
		stopMining();
		stand();
		AABB box = mc.player.getBoundingBox();
		float way = lane;
		double nearest = Double.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			Vec3 dir = Vec3.directionFromRotation(0, lane + 90 * i);
			for (double d = SWEEP_STEP / 2; d < nearest && d <= PORTAL_EXIT; d += SWEEP_STEP / 2) {
				AABB at = box.move(dir.scale(d));
				if (!mc.level.noCollision(mc.player, at)) break;
				if (inNetherPortal(at)) continue;
				nearest = d;
				way = lane + 90 * i;
			}
		}
		state = State.WALKING;
		spoof(way, 0);
		wantForward = true;
	}

	private boolean inNetherPortal(AABB box) {
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (mc.level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) return true;
		}
		return false;
	}

	/** Mines the block once it's in reach, walking along the lane up to it first. */
	private void mineOrApproach(BlockPos pos) {
		if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= MINE_REACH) {
			stand();
			if (!pos.equals(mining)) stopMining();
			// Asked every tick (asking again for the same block is free), so a refused or dropped break is tried again.
			mine(pos);
			state = State.MINING;
			progressCount = 0;
			return;
		}
		if (state == State.MINING) stopMining();
		state = State.WALKING;
		// Walk the lane's way whatever the camera does (the spoofed yaw moves you), back towards its line.
		spoof(steeredYaw(), 0);
		wantForward = true;
		trackStuck();
	}

	/**
	 * Counts the server's setbacks: a few in a short while (something the bounce keeps running into that isn't a
	 * block, or a check it keeps failing) count as the lane being blocked.
	 */
	private void countSetback() {
		long now = mc.level.getGameTime();
		setbacks.addLast(now);
		while (!setbacks.isEmpty() && now - setbacks.peekFirst() > SETBACK_WINDOW) setbacks.removeFirst();
		if (setbacks.size() >= SETBACK_LIMIT && state == State.BOUNCING) {
			setbacks.clear();
			forcedBlock = true;
		}
	}

	/** Whether you've been trying to move along the lane without getting anywhere; if so the lane counts as blocked. */
	private boolean trackStuck() {
		// Eating slows you nearly to a stop (and starts no glides, see bounce): that's not being stuck.
		if (mc.player.isUsingItem()) {
			progressCount = 0;
			return false;
		}
		double along = terrain() ? travelled : along(mc.player.position());
		int slot = progressCount % STUCK_TICKS;
		boolean stuck = progressCount >= STUCK_TICKS && along - progress[slot] < STUCK_PROGRESS;
		progress[slot] = along;
		progressCount++;
		if (!stuck) return false;
		progressCount = 0;
		forcedBlock = true;
		return true;
	}

	private void stopBouncing() {
		spoofing = false;
		holdGlide = false;
		letGo();
		putElytraBack();
	}

	/**
	 * Lets go of a held glide stop: Grim gets its answers and takes the glide as stopped, as the server already does, so
	 * the client stops gliding there too.
	 */
	private void letGo() {
		boolean cleared = Myriad.elytra().glideHold().cleared(this);
		Myriad.elytra().glideHold().disarm(this);
		if (cleared && mc.player != null && elytraOpen()) mc.player.stopFallFlying();
	}

	/** After bouncing with the chestplate on, the elytra goes back on. */
	private void putElytraBack() {
		if (chestMode && mc.player != null) Myriad.elytra().chestSwap().restoreElytra();
		chestMode = false;
	}

	// ---- Baritone -------------------------------------------------------------------------------------------------

	/**
	 * Hands over to Baritone: a goal on the lane's line past {@code distance} ahead (back onto the lane where you are,
	 * for 0), with a floor and room to bounce from, reached however Baritone can, mining and placing. Once it's there you
	 * face along the lane again and bouncing goes on. If it can't get there, the lane counts as blocked ({@code why}),
	 * until bouncing goes on again.
	 */
	private void handOff(double distance, String why) {
		stand();
		if (baritoneFailed) {
			blocked(why + " and Baritone can't get past");
			return;
		}
		Vec3 target = clearSpotPast(along(mc.player.position()) + distance);
		if (target == null) {
			baritoneFailed = true;
			blocked(why + " and there's nowhere to bounce from past it");
			return;
		}
		handoffPitch = mc.player.getXRot();
		breakBefore = Baritone.setSetting("allowBreak", true);
		placeBefore = Baritone.setSetting("allowPlace", true);
		// Never through a nether portal: it walks through them otherwise, and stopping in one takes you to another dimension.
		if (Baritone.getSetting("blocksToAvoid") instanceof List<?> avoid && !avoid.contains(Blocks.NETHER_PORTAL)) {
			List<Object> blocks = new ArrayList<>(avoid);
			blocks.add(Blocks.NETHER_PORTAL);
			avoidBefore = Baritone.setSetting("blocksToAvoid", blocks);
		}
		// It builds only with its throwaway blocks (dirt, cobblestone...): yours count too meanwhile (obsidian, say),
		// from the hotbar (one is brought in if they're all in the inventory).
		if (Baritone.getSetting("acceptableThrowawayItems") instanceof List<?> throwaway) {
			List<Object> items = new ArrayList<>(throwaway);
			var inv = mc.player.getInventory();
			for (int i = 0; i < 36; i++) {
				ItemStack stack = inv.getItem(i);
				if (isFiller(stack) && !items.contains(stack.getItem())) items.add(stack.getItem());
			}
			if (items.size() > throwaway.size()) {
				throwawayBefore = Baritone.setSetting("acceptableThrowawayItems", items);
				fillerSlot();
			}
		}
		// Recast says itself what a failure means for the lane (onFailed keeps core's own notice of it away).
		handoff = Myriad.jobs().start(fly, Step.of("Past it", () -> new PastIt(target, why)).named(why + ": Baritone")
			.onFailed(w -> baritoneFailed = true).finallyDo(this::endHandoff));
		status(why + ": Baritone is taking you past it", false);
		state = State.PATHING;
	}

	/** Baritone's settings back as they were. */
	private void endHandoff() {
		if (breakBefore != null) Baritone.setSetting("allowBreak", breakBefore);
		if (placeBefore != null) Baritone.setSetting("allowPlace", placeBefore);
		if (throwawayBefore != null) Baritone.setSetting("acceptableThrowawayItems", throwawayBefore);
		if (avoidBefore != null) Baritone.setSetting("blocksToAvoid", avoidBefore);
		breakBefore = placeBefore = throwawayBefore = avoidBefore = null;
	}

	/** Standing well to the side of the lane's line, or below its height. */
	private boolean offLane() {
		Vec3 pos = mc.player.position();
		return Math.abs(offLine(pos)) > RECENTRE_DISTANCE || !Double.isNaN(laneY) && pos.y < laneY - LANE_DROP;
	}

	/**
	 * While the hand-off job runs, you're Baritone's. Once it's done you face along the lane (Baritone turned you while it
	 * walked) and bounce on; if it failed, the lane counts as blocked. Cancelled (from the Jobs window, say), you wait
	 * at the block as if it were blocked, but If Blocked's disconnect is left out: the player stopped it.
	 */
	private void tickPathing() {
		spoofing = false;
		holdGlide = false;
		letGo();
		Job<Void> h = handoff;
		if (h == null || h.state().isActive()) return;
		handoff = null;
		if (h.state() == Job.State.DONE) {
			// As the lane was fixed: along it, the camera's own way round so it doesn't spin.
			mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(lane - mc.player.getYRot()));
			mc.player.setXRot(handoffPitch);
			state = State.BOUNCING;
			progressCount = 0;
			return;
		}
		baritoneFailed = true;
		Why why = h.why();
		if (h.state() == Job.State.FAILED && why != null && why.is(Why.BLOCKED)) {
			blocked(why.detail());
			return;
		}
		stand();
		state = State.BLOCKED;
		if (!warned) status("Baritone was stopped, waiting until it's clear", true);
		warned = true;
	}

	/**
	 * Baritone walking you to a spot past an obstacle, mining and placing as it must: done once you stand on the lane's
	 * line at the spot (or past it); failed as blocked if Baritone won't take the goal, stops short, or gets no closer
	 * for {@link #PATH_STALL} ticks. One goal per obstacle: it never tries another.
	 */
	private final class PastIt implements Step.Run<Void> {
		private final Vec3 target;
		private final String why;
		/** The closest it has got, ticks since it got closer, and ticks since Baritone last said it was going. */
		private double best = Double.MAX_VALUE;
		private int stall, idle;
		private boolean refused;

		PastIt(Vec3 target, String why) {
			this.target = target;
			this.why = why;
		}

		@Override
		public void start(JobContext ctx) {
			refused = !Baritone.pathTo(Mth.floor(target.x), Mth.floor(target.y + 0.1), Mth.floor(target.z));
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			if (refused) return Progress.failed(Why.of(Why.BLOCKED, why + " and Baritone can't get past"));
			Vec3 pos = mc.player.position();
			// At the goal's height too: left on top of what was in the way (Baritone climbed it), the goal can be just
			// ahead and below, and bouncing from up there runs straight back into it.
			if (mc.player.onGround() && along(pos) >= along(target) - 1 && Math.abs(offLine(pos)) <= 1 && Math.abs(pos.y - target.y) < 0.6
				&& !offLane()) {
				Baritone.stop();
				return Progress.done();
			}
			double left = pos.distanceTo(target);
			if (left < best - 0.5) {
				best = left;
				stall = 0;
			}
			// Give the pathfinder a moment to start (or to work out the next stretch) before taking it as having stopped.
			idle = Baritone.isPathing() ? 0 : idle + 1;
			if (++stall < PATH_STALL && idle < PATH_START) return Progress.running();
			Baritone.stop();
			return Progress.failed(Why.of(Why.BLOCKED, "Baritone couldn't get past"));
		}

		@Override
		public void pause(JobContext ctx) {
			Baritone.pause(ctx.job());
		}

		@Override
		public void resume(JobContext ctx) {
			Baritone.resume(ctx.job());
		}

		@Override
		public void cancel(JobContext ctx) {
			Baritone.resume(ctx.job());
			Baritone.stop();
		}

		@Override
		public String status() {
			return String.format("%.0f blocks to go", mc.player.position().distanceTo(target));
		}
	}

	/**
	 * The first spot from {@code from} along the line (and the one after it) you could stand and bounce from: with room
	 * overhead to hop if there's one within reach, otherwise anywhere you can stand (it walks on from there under the
	 * ceiling).
	 */
	private Vec3 clearSpotPast(double from) {
		for (double height : new double[]{JUMP_TOP, STAND_HEIGHT}) {
			for (double t = Math.ceil(from); t < from + PASS_SEARCH; t++) {
				if (standable(pointOnLine(t), height) && standable(pointOnLine(t + 1), height)) return pointOnLine(t);
			}
		}
		return null;
	}

	private boolean standable(Vec3 feet, double height) {
		AABB body = new AABB(feet.x - 0.3, feet.y + FLOOR_CLEARANCE, feet.z - 0.3, feet.x + 0.3, feet.y + height, feet.z + 0.3);
		if (!loaded(feet) || blocksIn(body, null)) return false;
		BlockPos floor = BlockPos.containing(feet.x, feet.y - 0.5, feet.z);
		return !mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty();
	}

	// ---- the lane --------------------------------------------------------------------------------------------------

	/** The way the lane runs: on terrain, where you look. */
	private Vec3 laneDir() {
		return Vec3.directionFromRotation(0, heading());
	}

	private float heading() {
		return terrain() ? mc.player.getYRot() : lane;
	}

	private boolean terrain() {
		return ground.get() == Ground.TERRAIN;
	}

	/**
	 * Fixes the lane: where you face, rounded as Snap says (the nearest highway direction by default), through the middle
	 * of the block you're on (on a highway, diagonal or not, that's a line through the middle of its blocks).
	 */
	private void fixLine() {
		var p = mc.player;
		lane = Mth.wrapDegrees(fly.snap(p.getYRot()));
		lineOrigin = new Vec3(p.getBlockX() + 0.5, p.getY(), p.getBlockZ() + 0.5);
	}

	/** How far {@code pos} is along the lane from where it was fixed. */
	private double along(Vec3 pos) {
		Vec3 dir = laneDir();
		return (pos.x - lineOrigin.x) * dir.x + (pos.z - lineOrigin.z) * dir.z;
	}

	/** How far {@code pos} is to the side of the lane's line (positive the way yaw turns). */
	private double offLine(Vec3 pos) {
		Vec3 dir = laneDir();
		return (pos.x - lineOrigin.x) * -dir.z + (pos.z - lineOrigin.z) * dir.x;
	}

	/** The point {@code t} blocks along the line, at the lane's height. */
	private Vec3 pointOnLine(double t) {
		Vec3 dir = laneDir();
		return new Vec3(lineOrigin.x + dir.x * t, Double.isNaN(laneY) ? floorY() : laneY, lineOrigin.z + dir.z * t);
	}

	/** The height you bounce off: where you last landed. */
	private double floorY() {
		return Double.isNaN(groundY) ? lineOrigin.y : groundY;
	}

	/**
	 * The flight yaw: the lane's, turned a little back towards its line when you've drifted to the side, by as much as
	 * brings you back without overshooting (the sideways speed you already have counts against it).
	 */
	private float steeredYaw() {
		if (terrain()) return mc.player.getYRot();
		Vec3 dir = laneDir(), v = mc.player.getDeltaMovement();
		double off = offLine(mc.player.position());
		double sideways = v.x * -dir.z + v.z * dir.x, forwards = Math.max(0.5, v.x * dir.x + v.z * dir.z);
		double want = Mth.clamp(-off * STEER_GAIN, -STEER_MAX_SPEED, STEER_MAX_SPEED);
		double aim = want + (want - sideways) * STEER_DAMPING;
		return lane + Mth.clamp((float) Math.toDegrees(Math.atan2(aim, forwards)), -STEER_MAX_TURN, STEER_MAX_TURN);
	}

	/** How far ahead to watch: enough to stop from this speed. */
	private double lookAhead() {
		Vec3 v = mc.player.getDeltaMovement();
		return LOOK_AHEAD + Math.sqrt(v.x * v.x + v.z * v.z) * LOOK_AHEAD_TICKS;
	}

	// ---- mining ---------------------------------------------------------------------------------------------------

	private void mine(BlockPos pos) {
		BlockState block = mc.level.getBlockState(pos);
		boolean again = pos.equals(mining);
		portalWait = again ? portalWait + 1 : 0;
		if (isPortal(block)) {
			// Once, and again every half second while it's still there.
			if (again && portalWait % 10 != 0) return;
			// Portals break instantly server-side but not client-side: send the dig directly.
			Direction face = Direction.getApproximateNearest(-laneDir().x, 0, -laneDir().z);
			Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, seq));
			Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, seq));
			mc.player.swing(InteractionHand.MAIN_HAND);
		} else {
			// The breaking service picks the tool, faces the block and swings, with Grim's timing.
			Myriad.breaking().breakBlock(this, pos, Rotations.PRIORITY_HIGH, Breaking.Options.PACKET.withRotate(true));
		}
		mining = pos.immutable();
	}

	private void stopMining() {
		mining = null;
		Myriad.breaking().cancel(this);
	}

	/**
	 * The first thing in the lane within {@code range}: blocks the player's hitbox would run into sliding along it (a
	 * little narrower than the hitbox, so brushing a wall doesn't count; from just above the floor to a standing
	 * player's height), chunks that haven't loaded, and, where the hops ahead come down ({@link #touchdowns}), nothing to
	 * land on for two blocks down (a hole you'd land in and not hop out of) or a ceiling low enough for the jump to hit. A hole or a low
	 * ceiling that the hops pass over isn't in the way. A ceiling is walked under rather than mined; it's what's ahead
	 * unless one of the others comes just after it. Null if it's clear.
	 */
	private Obstruction scanAhead(double range) {
		Vec3 dir = laneDir(), step = dir.scale(SWEEP_STEP);
		AABB box = mc.player.getBoundingBox();
		double floor = floorY();
		AABB body = new AABB(box.minX + 0.2, floor + FLOOR_CLEARANCE, box.minZ + 0.2, box.maxX - 0.2, floor + STAND_HEIGHT, box.maxZ - 0.2);
		Obstruction first = null;
		List<BlockPos> hits = new ArrayList<>();
		for (double d = 0; d <= range && first == null; d += SWEEP_STEP) {
			Vec3 at = dir.scale(d);
			if (!loaded(mc.player.position().add(at))) first = new Obstruction(Hazard.UNLOADED, d, List.of());
			else if (blocksIn(body.move(at).expandTowards(step), hits)) {
				Vec3 eye = mc.player.getEyePosition();
				hits.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(eye)));
				first = new Obstruction(Hazard.BLOCKS, d, hits);
			}
		}
		// A landing needs something to stand on within two blocks down, anywhere under the hitbox: a dip a block deep it
		// hops out of, and stopping for each (they're everywhere on worn highways, and fast hops come down often) costs
		// more than the rare landing in one. A hop's start rises standing.
		AABB support = new AABB(box.minX, floor - 2, box.minZ, box.maxX, floor - 0.5, box.maxZ);
		AABB headroom = new AABB(body.minX, floor + STAND_HEIGHT, body.minZ, body.maxX, floor + JUMP_TOP, body.maxZ);
		Obstruction ceiling = null;
		// Stopped: anywhere near. (Bouncing, not where you are: in the air over a hole is how a hop passes it.)
		double start = state == State.BOUNCING ? 0 : Math.min(START_SPAN, range);
		for (double d = 0; state != State.BOUNCING && d <= start; d += SWEEP_STEP / 2) {
			if (first != null && d > first.distance()) break;
			if (!blocksIn(support.move(dir.scale(d)), null)) first = new Obstruction(Hazard.HOLE, d, List.of());
			else if (ceiling == null && blocksIn(headroom.move(dir.scale(d)), null)) ceiling = new Obstruction(Hazard.CEILING, d, List.of());
		}
		// Where the hops come down is only known while they go on; stopped, what's near is what counts.
		for (Touchdown down : state == State.BOUNCING ? touchdowns(range) : List.<Touchdown>of()) {
			double back = down.exact() ? 0 : TOUCHDOWN_MARGIN, on = down.exact() ? 0 : TOUCHDOWN_MARGIN + TOUCHDOWN_ERROR * down.land();
			if (first != null && down.land() - back > first.distance()) break;
			// Under the landing; and on to the jump when it waits on the ground (it slides there), not otherwise.
			double until = hopGround > 1 ? down.takeoff() : down.land();
			for (double d = Math.max(start, down.land() - back); d <= until + on; d += SWEEP_STEP / 2) {
				if (blocksIn(support.move(dir.scale(d)), null)) continue;
				if (first == null || d < first.distance()) first = new Obstruction(Hazard.HOLE, d, List.of());
				break;
			}
			for (double d = Math.max(start, down.takeoff() - back); ceiling == null && d <= down.risen() + on; d += SWEEP_STEP / 2) {
				if (blocksIn(headroom.move(dir.scale(d)), null)) ceiling = new Obstruction(Hazard.CEILING, d, List.of());
			}
		}
		if (ceiling != null && (first == null || first.distance() > ceiling.distance() + UNDER_LOOK_AHEAD)) return ceiling;
		return first;
	}

	/**
	 * Where a hop ahead comes down, along the lane from where you are: where it lands (where the landing tick's move
	 * starts: it comes down before it goes on), where the jump after it leaves the ground (where that move ends, and
	 * further on when it waits there a tick or more) and where it has risen to its top, standing ({@link #RISE_TICKS}
	 * after). Exact for the landing you're standing on now.
	 */
	private record Touchdown(double land, double takeoff, double risen, boolean exact) {
	}

	/**
	 * The hops ahead, out to {@code range}: each as many ticks in the air and on the ground as the last (a glide climbs
	 * and falls the same at any speed), flown tick by tick as the glide's own maths has it (see {@link #glide}): the
	 * jump's push, the glide's drag, and the push forward as it levels out.
	 */
	private List<Touchdown> touchdowns(double range) {
		var p = mc.player;
		Vec3 v = p.getDeltaMovement(), dir = laneDir();
		double[] m = {Math.max(0, v.x * dir.x + v.z * dir.z), v.y};
		// A move goes down (or up) first and along after: a landing comes down where its tick's move starts.
		double d = 0, land = 0;
		boolean standing = p.onGround();
		for (int i = standing ? 0 : Math.max(1, hopAir - airTicks); i > 0; i--) {
			land = d;
			d += glide(m);
		}
		List<Touchdown> downs = new ArrayList<>();
		while (land <= range) {
			double landed = land;
			for (int i = 1; i < hopGround; i++) d += glide(m);
			double takeoff = d, risen = d;
			m[0] += JUMP_PUSH;
			m[1] = JUMP_POWER;
			for (int i = 0; i <= hopAir; i++) {
				land = d;
				d += glide(m);
				if (i < RISE_TICKS) risen = d;
			}
			downs.add(new Touchdown(landed, takeoff, risen, standing && downs.isEmpty()));
		}
		return downs;
	}

	/**
	 * One tick of a glide along the lane, {@code motion} being the speed along it and the vertical speed: at the pitch
	 * Recast flies (diving while rising, levelled out once falling; the last pitch flown, if a hook flies it otherwise).
	 * Returns how far along it went.
	 */
	private double glide(double[] motion) {
		double pitch = HOP.hooked() ? flownPitch : motion[1] > DIVE_UNTIL_Y ? 90 : 4;
		double cos = Math.cos(Math.toRadians(pitch)), lift = cos * cos;
		motion[1] += mc.player.getGravity() * (-1 + lift * 0.75);
		if (motion[1] < 0) {
			double push = motion[1] * -0.1 * lift;
			motion[0] += push;
			motion[1] += push;
		}
		motion[0] *= GLIDE_DRAG;
		motion[1] *= 0.98;
		return motion[0];
	}

	/**
	 * On terrain, the first thing within {@code range} where you look that a hop can't take: the ground there more than a
	 * block above what came before it, or something in the way above it up to a standing player's height (a wall, a
	 * tree, an overhang), the ground more than {@link #DROP_MAX} below (a fall would hurt), water or lava, or chunks that
	 * haven't loaded. Followed from one sample to the next, a little narrower than your hitbox, so a slope of steps is
	 * climbed one at a time. Notes the first step up worth a hop in {@link #rise}. Null if it's clear.
	 */
	private Obstruction scanTerrain(double range) {
		rise = null;
		Vec3 dir = laneDir();
		AABB box = mc.player.getBoundingBox();
		double floor = floorY(), top = floor;
		for (double d = SWEEP_STEP; d <= range; d += SWEEP_STEP) {
			Vec3 at = dir.scale(d);
			if (!loaded(mc.player.position().add(at))) return new Obstruction(Hazard.UNLOADED, d, List.of());
			AABB column = new AABB(box.minX + 0.2, top, box.minZ + 0.2, box.maxX - 0.2, top, box.maxZ - 0.2).move(at.x, 0, at.z);
			double ground = groundIn(column, top - DROP_MAX, top + STEP_MAX);
			if (Double.isNaN(ground) || fluidIn(column, ground)) return new Obstruction(Hazard.HOLE, d, List.of());
			List<BlockPos> hits = new ArrayList<>();
			if (blocksIn(new AABB(column.minX, ground + 0.05, column.minZ, column.maxX, ground + STAND_HEIGHT, column.maxZ), hits)) {
				return new Obstruction(Hazard.BLOCKS, d, hits);
			}
			if (rise == null && ground - floor >= STEP_ASSIST) rise = new Rise(d, ground - floor);
			top = ground;
		}
		return null;
	}

	/** The highest top of anything to stand on under {@code column}'s footprint, from {@code low} to {@code high}; NaN if none. */
	private double groundIn(AABB column, double low, double high) {
		double best = Double.NaN;
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(column.minX, low - 1, column.minZ), BlockPos.containing(column.maxX, high, column.maxZ))) {
			BlockState state = mc.level.getBlockState(pos);
			if (state.isAir()) continue;
			for (AABB part : state.getCollisionShape(mc.level, pos).toAabbs()) {
				AABB b = part.move(pos);
				if (b.maxX <= column.minX || b.minX >= column.maxX || b.maxZ <= column.minZ || b.minZ >= column.maxZ) continue;
				if (b.maxY < low || b.maxY > high + 1e-6) continue;
				if (Double.isNaN(best) || b.maxY > best) best = b.maxY;
			}
		}
		return best;
	}

	/** Water or lava standing on the ground at {@code ground} under {@code column}. */
	private boolean fluidIn(AABB column, double ground) {
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(column.minX, ground, column.minZ), BlockPos.containing(column.maxX, ground + 0.9, column.maxZ))) {
			if (!mc.level.getFluidState(pos).isEmpty()) return true;
		}
		return false;
	}

	/**
	 * On terrain, rising over the step ahead: it's within this hop's reach, and you're not yet over its top. The hop then
	 * rises without the dive (which turns height into speed) until you are: above the step's top from its third tick or
	 * so to its eleventh.
	 */
	private boolean climbing() {
		if (!terrain() || rise == null) return false;
		Vec3 v = mc.player.getDeltaMovement();
		return rise.distance() <= Math.hypot(v.x, v.z) * CLIMB_TICKS + 1 && mc.player.getY() < floorY() + rise.height() + CLIMB_MARGIN;
	}

	/**
	 * On terrain, standing, whether to let a tick go by without jumping because of the step ahead. A jump now has to be
	 * high enough by the time it reaches the step's face: it rises about 0.42, 0.75, 1.0 and 1.17 blocks in its first
	 * four ticks while you cover a tick's travel each (with the sprint jump's push), and the face can be most of a sample
	 * nearer than where it was found. Too close, it waits, sliding slower, until it can go up; slow enough, a jump goes up
	 * the face. And when the step is beyond a climb's reach, an ordinary hop now (about ten ticks, gaining a little speed)
	 * mustn't land too close to climb it from: then too it lets a tick go by, landing the next hop further on.
	 */
	private boolean holdBackForStep() {
		if (!terrain() || rise == null || !mc.player.onGround()) return false;
		Vec3 v = mc.player.getDeltaMovement();
		double speed = Math.hypot(v.x, v.z);
		if (speed < 0.15) return false;
		double d = rise.distance();
		if (!climbable(d, speed)) return true;
		if (d <= (speed + JUMP_PUSH) * CLIMB_TICKS) return false;
		double landed = d - (speed + JUMP_PUSH) * HOP_TICKS;
		return landed > 0 && landed <= (speed + HOP_GAIN + JUMP_PUSH) * CLIMB_TICKS && !climbable(landed, speed + HOP_GAIN);
	}

	/** Whether a jump now at {@code speed} rises over the step {@code distance} ahead before reaching it. */
	private boolean climbable(double distance, double speed) {
		double h = rise.height();
		int ticks = h <= 0.42 ? 1 : h <= 0.75 ? 2 : h <= 1.0 ? 3 : 4;
		return distance - 0.7 >= (speed + JUMP_PUSH) * ticks;
	}

	/** Whether anything in {@code box} is in the way (see {@link #obstructs}); each such block is added to {@code hits}. */
	private boolean blocksIn(AABB box, List<BlockPos> hits) {
		boolean any = false;
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (!obstructs(pos, box)) continue;
			if (hits == null) return true;
			hits.add(pos.immutable());
			any = true;
		}
		return any;
	}

	private boolean loaded(Vec3 pos) {
		return mc.level.hasChunk(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z));
	}

	/**
	 * Whether the block at {@code pos} is something to clear: a portal, a cobweb (no collision, but it stops you dead),
	 * or a collision shape that meets {@code box}.
	 */
	private boolean obstructs(BlockPos pos, AABB box) {
		if (PASS_THROUGH.hooked() && Boolean.TRUE.equals(PASS_THROUGH.call(this, pos.immutable(), p -> false))) return false;
		BlockState state = mc.level.getBlockState(pos);
		if (isPortal(state) || state.is(Blocks.COBWEB)) return true;
		if (state.isAir() || !state.getFluidState().isEmpty()) return false;
		return state.getCollisionShape(mc.level, pos).toAabbs().stream().anyMatch(part -> part.move(pos).intersects(box));
	}

	private static boolean isPortal(BlockState state) {
		return state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);
	}
}