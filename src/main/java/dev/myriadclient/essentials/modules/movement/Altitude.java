package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.util.ItemInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * Crosses open country without fireworks, "pitch 40" style. It dives until you're fast, pulls up hard, then eases back to
 * level: pulling up gives back more height than the speed it costs, so the cycle holds the altitude you started gliding
 * at (it dives harder when you're above it and climbs more when you're below). Start high, since the first dive from a
 * slow glide drops you 50 or so blocks. Steer with the camera, or turn on Lock Rotation to keep flying the way you faced
 * when the glide started (Snap rounds it to 45° or 90°); the flight's pitch (and a locked heading) only goes to the
 * server and the flight physics, so you can look around freely. With No Durability each glide start waits for the
 * server to have the elytra on (see {@link NoDurability}); without it the elytra wears, and once it's down to what
 * landing from here takes, a spare from your inventory goes on in mid-air. Made to be left alone: when the server sets
 * you back a few times in a row it glides gently until the server agrees again, and it slows down (climbing) while the
 * chunks ahead haven't loaded; however often the server sets you back, it flies on (a dropped glide starts again), so
 * leave Auto Disconnect's Hard Deck on to catch a flight that goes wrong. When the last elytra is wearing out it comes
 * down to land, levelling out near the ground, and After Landing disconnects you there or leaves you standing.
 */
public class Altitude extends ElytraMode {
	public enum AfterLanding {
		STAY, DISCONNECT
	}

	private enum Phase {
		DIVE, PULL_UP, CLIMB
	}

	private final EnumSetting<AfterLanding> afterLanding = sgGeneral.oneOf("After Landing", AfterLanding.DISCONNECT)
		.description("When it has to come down (the last elytra wearing out): disconnect once landed, or stay there.").visible(this::chosen).build();

	/**
	 * Worked out on vanilla's glide physics: dive at {@code DIVE_PITCH} until faster than the threshold (blocks a
	 * second), snap up to {@code CLIMB_PITCH} at {@code PULL_RATE} a tick, then ease back to level at {@code EASE_RATE}.
	 * The threshold rises by {@code HOLD_GAIN} for every block above the cruise height (more speed, less climb) and falls
	 * below it, which keeps the cycle at that height: about 28 blocks a second.
	 */
	private static final float DIVE_PITCH = 36, CLIMB_PITCH = -49, PULL_RATE = 5, EASE_RATE = 0.8f;
	private static final double DIVE_SPEED = 52, HOLD_GAIN = 0.2, MIN_DIVE_SPEED = 36, MAX_DIVE_SPEED = 58;
	/**
	 * With No Durability: a slower cycle, about 18 blocks a second, so that 2b2t's chunks (sent just in time in
	 * new terrain) are in well before the swaps reach them. Down there the climb gains most from about 40 and nothing
	 * from 33, so the threshold works the other way round: it falls by {@code SLOW_HOLD_GAIN} for every block above the
	 * cruise height (less climb) and rises below it. {@code SLOW_LATE_SPEED} while chunks still come in late.
	 */
	private static final double SLOW_DIVE_SPEED = 34.5, SLOW_HOLD_GAIN = 0.1, SLOW_MIN_DIVE_SPEED = 33, SLOW_MAX_DIVE_SPEED = 40, SLOW_LATE_SPEED = 36;
	/**
	 * Steadying: this many setbacks within {@code STEADY_WINDOW} ticks, and it glides at {@code STEADY_PITCH}
	 * (a gentle descent) for {@code STEADY_TICKS} rather than dive and climb. Grim sends a setback again every couple of
	 * ticks until the client takes it: teleports within {@code SAME_SETBACK} ticks of the last one counted are that one.
	 */
	private static final int STEADY_SETBACKS = 3, STEADY_WINDOW = 60, STEADY_TICKS = 60, SAME_SETBACK = 10;
	private static final float STEADY_PITCH = 8;
	/**
	 * Chunks this many seconds of flight ahead that haven't loaded slow you down (climbing at
	 * {@code SLOW_PITCH} trades speed for height) until they do, down to {@code SLOW_SPEED} blocks a second.
	 */
	private static final double LOAD_AHEAD_SECONDS = 4, SLOW_SPEED = 15;
	private static final float SLOW_PITCH = -20;
	/**
	 * Over terrain: how far ahead the ground is checked (loaded chunks' height maps, along your heading and a
	 * few blocks to each side), and how high above the highest of it the cruise height is kept.
	 */
	private static final int TERRAIN_AHEAD = 160, TERRAIN_STEP = 4, TERRAIN_SIDE = 4;
	private static final double TERRAIN_CLEARANCE = 24;
	/** Closer than this many ticks of flight, ground up to this far below you means pull up, whatever the cycle. */
	private static final int PULL_UP_TICKS = 60;
	private static final double PULL_UP_MARGIN = 8;
	/**
	 * The pitch it comes down at once the elytra is wearing out, and how fast that descends (blocks a second,
	 * on the slow side). The server wears a point every second of gliding; with fewer left than the way down takes (and
	 * a margin), it lands while it still can.
	 */
	private static final float LAND_PITCH = 20;
	private static final double LAND_DESCENT = 3, LAND_MARGIN = 30;
	/** Landing: this close above the ground it levels out, so the touchdown is gentle (and the server's fall count forgotten). */
	private static final double FLARE_HEIGHT = 16;

	private Phase phase = Phase.DIVE;
	private float climbPitch;
	private double cruiseY = Double.NaN;
	/** Coming down to land (the elytra nearly worn out, or it can't go on), and why. */
	private boolean landing;
	private String landReason = "";
	/** Tick counts of recent setbacks, and ticks left gliding steadily after a run of them. */
	private final ArrayDeque<Integer> setbacks = new ArrayDeque<>();
	private int steadyTicks;

	Altitude(ElytraFly fly) {
		super(fly, ElytraFly.ALTITUDE);
	}

	@Override
	protected void resetFlight() {
		phase = Phase.DIVE;
		cruiseY = Double.NaN;
		landing = false;
		landReason = "";
		setbacks.clear();
		steadyTicks = 0;
	}

	@Override
	public String hudInfo() {
		return landing ? "Landing" : steadyTicks > 0 ? "Steadying" : Double.isNaN(cruiseY) ? null : String.format("Y%.0f", cruiseY);
	}

	/** Long fast glides: each start waits for the server to have the elytra back on (see ElytraSwap.Behaviour). */
	@Override
	protected boolean waitsForElytra() {
		return true;
	}

	/** It can't keep the flight safe: it comes down to land, and After Landing decides what then. */
	private void cannotGoOn(String why) {
		if (landing) return;
		landing = true;
		landReason = why;
		warn("Coming down to land: " + why);
	}

	@Override
	protected void tickFlight() {
		var p = mc.player;
		if (flagged) {
			flagged = false;
			countSetback(p);
		}
		if (landing && (p.onGround() || p.isInWater())) landed();
		if (!canGlide()) {
			spoofing = false;
			return;
		}
		if (!p.isFallFlying()) {
			spoofing = false;
			phase = Phase.DIVE;
			cruiseY = Double.NaN;
			// Open the elytra once you're falling: off a ledge, or on the way down from a jump.
			if (!p.onGround() && p.getDeltaMovement().y < -0.3) startGliding();
			return;
		}
		if (Double.isNaN(cruiseY)) cruiseY = p.getY();
		float yaw = heading(p.getYRot());
		if (!landing && elytraWearingOut()) {
			int spare = spareElytra();
			if (spare < 0) cannotGoOn("last elytra nearly worn out");
			else if (Myriad.inventory().prepareClick()) Myriad.inventory().move(spare, ItemInfo.inventoryIndex(EquipmentSlot.CHEST));
		}
		// Higher ground ahead raises the cruise height in time to clear it (it's never lowered again by itself).
		Vec3 heading = Vec3.directionFromRotation(0, yaw);
		cruiseY = Math.max(cruiseY, groundAhead(heading, TERRAIN_AHEAD) + TERRAIN_CLEARANCE);

		double speed = p.getDeltaMovement().length() * 20;
		// Too close to clear by cruising: pull up and keep climbing (slowing down if it must: settling onto a slope
		// slowly beats hitting it at full speed).
		double near = Math.max(16, Math.sqrt(p.getDeltaMovement().horizontalDistanceSqr()) * PULL_UP_TICKS);
		if (groundAhead(heading, near) > p.getY() - PULL_UP_MARGIN) {
			climbPitch = CLIMB_PITCH;
			phase = Phase.CLIMB;
			spoof(yaw, CLIMB_PITCH);
			return;
		}
		if (landing) {
			double above = p.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
			spoof(yaw, above > FLARE_HEIGHT ? LAND_PITCH : 0);
			return;
		}
		// Set back a few times in a row: glide gently, no diving or climbing, until the server agrees again.
		if (steadyTicks > 0) {
			steadyTicks--;
			phase = Phase.DIVE;
			spoof(yaw, STEADY_PITCH);
			return;
		}
		// Chunks ahead not in yet: slow down (climbing) until they are, rather than fly into the edge of the world.
		if (speed > SLOW_SPEED && !loadedAhead(heading, speed * LOAD_AHEAD_SECONDS)) {
			phase = Phase.DIVE;
			spoof(yaw, SLOW_PITCH);
			return;
		}
		double diveUntil = Mth.clamp(DIVE_SPEED + HOLD_GAIN * (p.getY() - cruiseY), MIN_DIVE_SPEED, MAX_DIVE_SPEED);
		if (chestSwaps()) {
			diveUntil = Mth.clamp(SLOW_DIVE_SPEED - SLOW_HOLD_GAIN * (p.getY() - cruiseY), SLOW_MIN_DIVE_SPEED, SLOW_MAX_DIVE_SPEED);
			// While No Durability's swaps wait for chunks, the slowest that still climbs.
			if (noDur.chunksLate()) diveUntil = Math.min(diveUntil, SLOW_LATE_SPEED);
		}
		float flightPitch = switch (phase) {
			case DIVE -> {
				if (speed > diveUntil) {
					phase = Phase.PULL_UP;
					climbPitch = 0;
				}
				yield DIVE_PITCH;
			}
			case PULL_UP -> {
				climbPitch = Math.max(CLIMB_PITCH, climbPitch - PULL_RATE);
				if (climbPitch <= CLIMB_PITCH) phase = Phase.CLIMB;
				yield climbPitch;
			}
			case CLIMB -> {
				climbPitch = Math.min(0, climbPitch + EASE_RATE);
				if (climbPitch >= 0) phase = Phase.DIVE;
				yield climbPitch;
			}
		};
		spoof(yaw, flightPitch);
	}

	/** The inventory index of the freshest other elytra with more uses left than landing from here takes, or -1. */
	private int spareElytra() {
		double need = landingUses();
		return Myriad.inventory().bestInInventory(s -> ItemInfo.isGlider(s) && !ItemInfo.isBound(s) && (!s.isDamageableItem() || ItemInfo.durability(s) > need)
			? 1 + ItemInfo.durabilityFraction(s) : 0);
	}

	/** Down safely. After Landing disconnects you or leaves you there. */
	private void landed() {
		landing = false;
		String why = landReason.isEmpty() ? "landed" : "landed: " + landReason;
		landReason = "";
		if (afterLanding.get() == AfterLanding.DISCONNECT) leave(why.substring(0, 1).toUpperCase() + why.substring(1));
		else warn(why.substring(0, 1).toUpperCase() + why.substring(1));
	}

	/** A run of setbacks steadies the flight for a while. */
	private void countSetback(LocalPlayer p) {
		int now = p.tickCount;
		if (!setbacks.isEmpty() && now - setbacks.peekLast() < SAME_SETBACK) return;
		setbacks.addLast(now);
		while (!setbacks.isEmpty() && now - setbacks.peekFirst() > STEADY_WINDOW) setbacks.removeFirst();
		if (setbacks.size() >= STEADY_SETBACKS) steadyTicks = STEADY_TICKS;
	}

	/** Whether every chunk within {@code distance} blocks along {@code heading} has loaded. */
	private boolean loadedAhead(Vec3 heading, double distance) {
		var p = mc.player;
		for (double d = 0; d <= distance; d += 8) {
			if (!mc.level.hasChunk(Mth.floor(p.getX() + heading.x * d) >> 4, Mth.floor(p.getZ() + heading.z * d) >> 4)) return false;
		}
		return true;
	}

	/**
	 * Whether the worn elytra has fewer uses left than gliding down from here takes (a point a second while the server
	 * glides you), with a margin. No Durability keeps it from wearing, but only while it can swap.
	 */
	private boolean elytraWearingOut() {
		var p = mc.player;
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if (!ItemInfo.isGlider(chest) || !chest.isDamageableItem()) return false;
		return ItemInfo.durability(chest) < landingUses();
	}

	/** Elytra uses that gliding down from here takes (a point a second while the server glides you), with a margin. */
	private double landingUses() {
		var p = mc.player;
		double above = p.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
		return above / LAND_DESCENT + LAND_MARGIN;
	}

	/**
	 * The highest ground (any block that stops movement, trees and water included) within {@code distance} blocks
	 * along {@code heading}, and {@link #TERRAIN_SIDE} to each side; only chunks that have loaded count.
	 */
	private double groundAhead(Vec3 heading, double distance) {
		var p = mc.player;
		double best = Double.NEGATIVE_INFINITY;
		for (double d = 0; d <= distance; d += TERRAIN_STEP) {
			for (int side = -TERRAIN_SIDE; side <= TERRAIN_SIDE; side += TERRAIN_SIDE) {
				int x = Mth.floor(p.getX() + heading.x * d - heading.z * side), z = Mth.floor(p.getZ() + heading.z * d + heading.x * side);
				if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
				best = Math.max(best, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
			}
		}
		return best;
	}
}
