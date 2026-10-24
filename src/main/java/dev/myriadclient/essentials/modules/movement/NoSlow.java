package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.DisconnectEvent;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.util.Packets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves at speed while you eat, drink, block or draw a bow, and through cobwebs and sweet berry bushes.
 * <p>
 * Items: where the server simulates movement (Grim), one tick in three at full speed. Grim tries each tick both with
 * the item's slowdown and without, so movement itself passes, and its NoSlow check flags only two ticks in a row it
 * can't clear while it thinks you use an item ({@link #CYCLE} says why that takes two slowed ticks after a fast one):
 * about twice the walking speed the item allows. Elsewhere, every tick.
 * <p>
 * Webs: Grim always simulates a web's slowdown while there's one in its world where you are, so under Grim the webs
 * round you are taken out of its world for as long as you're in them ({@link WebClearer}). Elsewhere they're ignored.
 */
public class NoSlow extends Module {
	private final BoolSetting items = sgGeneral.bool("Items").description("Eat, drink, block and draw a bow without slowing to a crawl.").defaultValue(true).build();
	private final BoolSetting webs = sgGeneral.bool("Webs").description("Walk through cobwebs and sweet berry bushes at full speed.").defaultValue(true).build();

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
	private final WebClearer webClearer = new WebClearer();
	private final DigTarget digTarget = new DigTarget();

	public NoSlow() {
		super(Tags.MOVEMENT, "No Slow", "Move at speed while eating or using items, and through cobwebs.");
		Myriad.events().subscribe(digTarget);
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
		if (webs.get() && Myriad.antiCheat().isStrict() && digTarget.finishesPass()) webClearer.start();
	}

	private static boolean slows(BlockState state) {
		return state.is(Blocks.COBWEB) || state.is(Blocks.SWEET_BERRY_BUSH);
	}

	/** The item-use speed for this tick: full while No Slow is on and this tick goes at speed, {@code slowed} otherwise. */
	public static float itemSpeed(float slowed) {
		NoSlow noSlow = Modules.active(NoSlow.class);
		return noSlow != null && noSlow.items.get() && noSlow.fullSpeed ? 1 : slowed;
	}

	/** Whether {@code state} leaves you free to move: a web or berry bush with Webs on, or while webs are being cleared. */
	public static boolean ignores(BlockState state) {
		NoSlow noSlow = Modules.get(NoSlow.class);
		if (noSlow == null || !slows(state)) return false;
		return noSlow.webClearer.active || noSlow.isEnabled() && noSlow.webs.get() && !Myriad.antiCheat().isStrict();
	}

	/**
	 * Keeps the webs round you air in Grim's world. A finished dig reported for a block makes Grim hold air there until
	 * the client answers the ping it sends just before the server's acknowledgement, about a round trip, though the
	 * server refuses the break and nothing changes on screen. A web is reported again the moment that acknowledgement is
	 * handled: the ping before it has been answered, so Grim has the web back (a report while it still holds air is a
	 * break of air, AirLiquidBreak), and no movement packet has gone out between. To pass Grim's break checks the finish
	 * follows a cancel of the same block (WrongBreak) sent on its bottom face (PositionBreakB), and names a face the eyes
	 * are outside of (PositionBreakA).
	 * <p>
	 * It keeps going after No Slow or Webs goes off until you're out of the webs: Grim would restore one while you're in
	 * it, and the client would have to stop ignoring it on that very tick.
	 */
	private final class WebClearer {
		/**
		 * How far round you webs are cleared, past the tick's movement so far: more than a tick of speeding up, and
		 * Grim's movement threshold. Each cleared web costs two packets a round trip, and Paper kicks at 500 a second.
		 */
		private static final double MARGIN = 0.15;
		/** Headroom on the ground for a jump starting this tick, which the movement so far doesn't show. */
		private static final double JUMP = 0.45;
		/** How far a sprint jump carries you before you land, about. */
		private static final double JUMP_REACH = 4;
		/** The lowest and highest eyes Grim allows a player, crawling and standing. */
		private static final double EYE_LOW = 0.4, EYE_HIGH = 1.62;
		private static final Direction[] FACES = {Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};

		/** Webs reported broken and not yet answered. */
		private final Set<BlockPos> reported = new HashSet<>();
		/** The last block a cancelled dig went out for, as Grim's WrongBreak remembers it. */
		private BlockPos lastCancelled;
		private boolean active;

		void start() {
			if (active) return;
			active = true;
			// What was cancelled before isn't known: the first report of each block cancels it first.
			lastCancelled = null;
			Myriad.events().subscribe(this);
		}

		private void stop() {
			active = false;
			reported.clear();
			Myriad.events().unsubscribe(this);
		}

		@Subscribe(inGame = true)
		private void onTick(TickEvent.Pre e) {
			// Every finish would be refused: you go slowed, as Grim has it.
			if (!digTarget.finishesPass()) {
				stop();
				return;
			}
			List<BlockPos> near = websNear();
			boolean wanted = isEnabled() && webs.get() && Myriad.antiCheat().isStrict();
			if (!wanted && near.isEmpty()) {
				stop();
				return;
			}
			for (BlockPos web : near) {
				if (!reported.contains(web)) report(web);
			}
		}

		/**
		 * No sprint jump into webs. The server has them, so its own movement check slows your move as a web does and lets
		 * the difference run to 0.25 blocks a tick (Paper's moved-wrongly threshold): about 0.33 blocks a tick through
		 * webs. Walking and sprinting stay under it; a sprint jump's push goes over, and its speed lasts the whole jump,
		 * so none starts with webs within a jump's reach ahead.
		 */
		@Subscribe
		private void onInput(InputEvent e) {
			if (!e.jump || mc.player == null || !mc.player.isSprinting() && !e.sprint) return;
			Vec3 ahead = Vec3.directionFromRotation(0, mc.player.getYRot()).scale(JUMP_REACH);
			AABB arc = mc.player.getBoundingBox().expandTowards(ahead.x, 1.25, ahead.z).inflate(0.3);
			if (websIn(arc)) e.jump = false;
		}

		private boolean websIn(AABB box) {
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
				if (slows(mc.level.getBlockState(pos))) return true;
			}
			return false;
		}

		private List<BlockPos> websNear() {
			Vec3 move = mc.player.getDeltaMovement();
			AABB box = mc.player.getBoundingBox().expandTowards(move).inflate(MARGIN).expandTowards(0, mc.player.onGround() ? JUMP : 0, 0);
			List<BlockPos> near = new ArrayList<>();
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
				if (slows(mc.level.getBlockState(pos))) near.add(pos.immutable());
			}
			return near;
		}

		private void report(BlockPos web) {
			if (!web.equals(lastCancelled)) Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, web, Direction.DOWN, seq));
			Direction face = faceFor(web);
			reported.add(web);
			Packets.sendSequenced(web, seq -> new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, web, face, seq))
				.whenComplete((state, failure) -> answered(web));
		}

		private void answered(BlockPos web) {
			if (!reported.remove(web) || !active || mc.player == null) return;
			if (websNear().contains(web)) report(web);
		}

		/**
		 * A face of {@code pos} Grim's PositionBreakA takes from where you are: it judges from your eyes anywhere from 0.4
		 * to 1.62 above your feet (any pose), at the position it last had, which can be the tick before this one's. A
		 * face passes from a position when those eyes are inside the block, or all outside it on that face's side.
		 */
		private Direction faceFor(BlockPos pos) {
			Vec3 now = mc.player.position(), before = new Vec3(mc.player.xo, mc.player.yo, mc.player.zo);
			for (Direction face : FACES) {
				if (passes(face, pos, now) && passes(face, pos, before)) return face;
			}
			return Direction.UP;
		}

		private static boolean passes(Direction face, BlockPos pos, Vec3 feet) {
			double low = feet.y + EYE_LOW, high = feet.y + EYE_HIGH;
			boolean inColumn = feet.x >= pos.getX() && feet.x <= pos.getX() + 1 && feet.z >= pos.getZ() && feet.z <= pos.getZ() + 1;
			if (inColumn && high >= pos.getY() && low <= pos.getY() + 1) return true;
			return switch (face) {
				case UP -> high >= pos.getY() + 1;
				case DOWN -> low <= pos.getY();
				case EAST -> feet.x >= pos.getX() + 1;
				case WEST -> feet.x <= pos.getX();
				case SOUTH -> feet.z >= pos.getZ() + 1;
				case NORTH -> feet.z <= pos.getZ();
			};
		}

		@Subscribe(packets = ServerboundPlayerActionPacket.class)
		private void onSend(PacketEvent.Send e) {
			if (e.isCancelled() || !(e.packet() instanceof ServerboundPlayerActionPacket p)) return;
			if (p.getAction() == Action.START_DESTROY_BLOCK) lastCancelled = null;
			else if (p.getAction() == Action.ABORT_DESTROY_BLOCK) lastCancelled = p.getPos();
		}

		@Subscribe
		private void onLeave(WorldEvent.Leave e) {
			stop();
		}
	}

	/**
	 * The block Grim's FastBreak times every finish against: the last one a dig was started for, by anyone (vanilla
	 * mining, Packet Mine), followed from the dig packets sent whether No Slow is on or not. While it still stands and
	 * takes more than a hit, a web's finish comes all its break time early (a tap on stone, given up, makes it 7.5
	 * seconds), and Grim refuses it.
	 */
	private final class DigTarget {
		private BlockPos target;

		boolean finishesPass() {
			if (target == null || mc.player == null || mc.level == null) return true;
			BlockState state = mc.level.getBlockState(target);
			return state.isAir() || state.getDestroyProgress(mc.player, mc.level, target) >= 1;
		}

		@Subscribe(packets = ServerboundPlayerActionPacket.class)
		private void onSend(PacketEvent.Send e) {
			if (!e.isCancelled() && e.packet() instanceof ServerboundPlayerActionPacket p && p.getAction() == Action.START_DESTROY_BLOCK) target = p.getPos();
		}

		@Subscribe
		private void onDisconnect(DisconnectEvent e) {
			target = null;
		}
	}
}
