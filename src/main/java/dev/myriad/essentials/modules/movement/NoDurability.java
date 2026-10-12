package dev.myriad.essentials.modules.movement;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.Subscription;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.InteractEvent;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.util.MathUtil;
import dev.myriad.essentials.mixin.FireworkRocketEntityAccessor;
import dev.myriad.essentials.util.ChestSwap;
import dev.myriad.essentials.util.GlideHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra Fly's No Durability, and its catch of a glide dropped in mid-air (on whenever Elytra Fly is).
 * <p>
 * In Altitude it goes the other way round (see {@link #tickBreaks}): the elytra stays on and the server glides you, and
 * just before the server's glide would wear it (every 20 ticks without a break) there's a break: the chestplate on,
 * the server's stop held, the elytra back on and the glide started again. The server agrees with you most of the time
 * (on 2b2t, swapping all the time lost too many restarts on Altitude's long fast glides, each one a run of setbacks),
 * and a restart it doesn't answer is simply made again, the elytra being on already.
 * <p>
 * The server wears an elytra by one for every 20 ticks of gliding without a break. With a
 * chestplate in your off hand or hotbar, the elytra is swapped for it (using it, as a right-click equips armour)
 * while you glide, so the server stops the glide on its next tick; the client keeps gliding through that (see
 * {@link GlideHold}), and Grim doesn't notice. Every 8 ticks the elytra goes back on just long enough to start the
 * glide again (jump pressed, as vanilla starts one), and comes off again. The server never glides for more than a
 * tick in one go, so it never wears the elytra. Rockets you use meanwhile go out in those moments, when the server
 * takes them.
 * <p>
 * Meanwhile the server takes you as falling, and counts every block you descend as fall distance; only rising again
 * clears it (or gliding long enough at a gentle slope once the elytra's back on). Landing, or hitting something,
 * with that count would cost all of it. So swaps only run with room around you: below, and along the way you're flying
 * for as long as the server takes to hear from you, or, once you've come down more than a safe fall since you last
 * rose, for as long as it then takes to glide that count away too. The elytra is back on and the server gliding before
 * you could land or hit anything. The server's equip sound for each swap is muted. Eating (or anything you hold to use) pauses the swapping: Grim stops what
 * you're using at every swap, so the elytra goes back on at the next start, and swapping picks up again once you're
 * done.
 */
final class NoDurability {
	private final ElytraFly fly;
	private final Minecraft mc = Minecraft.getInstance();

	NoDurability(ElytraFly fly) {
		this.fly = fly;
	}

	/** Ticks between glide restarts: well under vanilla's 80-tick floating kick and the 20 ticks that wear the elytra. */
	private static final int INTERVAL = 8;
	/** Give up if the server hasn't stopped the glide this long after a swap (it refused it). */
	private static final int CLEAR_TIMEOUT = 40;
	/** Ticks of gliding with room below before the first swap. */
	private static final int ARM_TICKS = 10;
	/** Coming down more than this since you last rose, the server's count of your fall would hurt on landing. */
	private static final double SAFE_DESCENT = 3;
	/**
	 * Altitude: ticks of gliding (from the last start) before a break; the server wears the elytra at 20, and the break
	 * reaches it half a round trip later.
	 */
	private static final int BREAK_AFTER = 13;
	/** Vanilla's gliding bit in the entity flag byte. */
	private static final int GLIDING_BIT = 1 << 7;
	/** Network thread: the server's glide as your entity flags say, and how many times it has started. */
	private static volatile boolean serverGliding;
	private static volatile int serverStarts;

	/**
	 * Ticks of gliding the server needs, elytra back on, before it lets go of that count: it only does while it sees you
	 * descend gently, and its idea of your speed (a free fall while it wasn't gliding you) takes a while to settle.
	 */
	private static final int FORGET_TICKS = 30;

	private boolean swapping, startNow, rocketWanted, firing, warned;
	/** Said why the counterpart isn't at hand (once, until it is). */
	private boolean pairWarned;
	/**
	 * Stopping in mid-air: the elytra is back on, and the glide starts again once the server's stop for the last swap
	 * is in (held meanwhile, so you keep gliding) and not straight after the last start; {@link #finishWait} counts the
	 * ticks spent waiting for it.
	 */
	private boolean finishing;
	private int finishWait;
	/** How far you've come down, as the server counts it, since you last rose; and ticks of gliding since swapping. */
	private double descent;
	private int glidingTicks;
	/** Something to eat or hold to use was used meanwhile: swapping stops at the next start, so it can go then. */
	private boolean stopForUse;
	private InteractionHand rocketHand = InteractionHand.MAIN_HAND;
	/** The hotbar slot a waiting rocket was used from (it may have been a silent swap, as Middle Click's are). */
	private int rocketSlot = -1;
	private int armTicks, sinceStart;
	/**
	 * Altitude: a break is on (chestplate on, waiting for the server's stop), then the elytra is back on and its start
	 * waits for the server to have it on; ticks gliding since the last start.
	 */
	private boolean breaking, equipping;
	private int glidingFor;
	/** Altitude: ticks since a start the server hasn't answered yet (-1: none), and its starts before it. */
	private int unanswered = -1, startsBefore;

	void enable() {
		swapping = startNow = rocketWanted = stopForUse = finishing = breaking = equipping = false;
		glidingFor = 0;
		unanswered = -1;
		descent = 0;
		armTicks = catching = 0;
	}

	void disable() {
		if (mc.player != null && mc.level != null) {
			// No next tick to wait for: start the glide again now rather than drop out of it in mid-air.
			finish(true);
			if (breaking || equipping) endBreak(true);
			// Turning off mid-swap can start the glide again, and Elytra Fly no longer hears the input event that presses
			// jump with it (Grim's ElytraB): press it from a listener of its own, for that one tick.
			if (startNow) pressJumpOnce();
		}
		swapping = startNow = breaking = equipping = false;
		unanswered = -1;
		catching = 0;
		GlideHold.disarm(this);
	}

	/** Whether the elytra and chestplate are being swapped (or the elytra put back for good) right now. */
	boolean swapping() {
		return swapping || breaking || equipping;
	}

	/** Network thread, from Elytra Fly: the local player's entity flags, as the server sent them. */
	static void onOwnFlags(byte flags) {
		boolean gliding = (flags & GLIDING_BIT) != 0;
		if (gliding && !serverGliding) serverStarts++;
		serverGliding = gliding;
	}

	/** Before any of this tick's actions, after Elytra Fly's flight: a restart sends held ping answers, which must come first (Grim's Post). */
	void tick() {
		startNow = false;
		if (mc.player == null || mc.level == null) {
			swapping = false;
			return;
		}
		catchGlide(mc.player);
		tickNoDurability();
	}

	private static void pressJumpOnce() {
		Subscription[] once = new Subscription[1];
		once[0] = Myriad.events().listen(InputEvent.class, Priority.LOWEST, e -> {
			e.jump = true;
			once[0].unsubscribe();
		});
	}

	// ---- No Durability ----------------------------------------------------------------------------------------------

	private void tickNoDurability() {
		LocalPlayer p = mc.player;
		trackDescent(p);
		if (!ElytraFly.noDurability()) {
			finish();
			if (breaking || equipping) endBreak(true);
			return;
		}
		if (ElytraFly.altitude()) {
			finish();
			tickBreaks(p);
			return;
		}
		if (breaking || equipping) endBreak(true);
		if (!swapping) {
			// Elytra Fly's bounce does its own swapping.
			// Not while you eat (or use anything held): a swap would stop it (see onUse).
			boolean ready = p.isFallFlying() && ChestSwap.elytraWorn() && !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.isUsingItem()
				&& !ElytraFly.holdsGlide() && room(true);
			armTicks = ready ? armTicks + 1 : 0;
			if (armTicks < ARM_TICKS) return;
			if (!chestplateAtHand(true)) return;
			if (!ChestSwap.ready()) {
				warnOnce("No Durability can't swap armour with Curse of Binding.");
				return;
			}
			if (!GlideHold.arm(this)) return;
			ChestSwap.swap();
			swapping = true;
			sinceStart = 0;
			return;
		}
		sinceStart++;
		if (finishing) {
			finish();
			return;
		}
		// Landing, water, or the ground (below or ahead) close enough that the server could see you land without the
		// elytra.
		if (!p.isFallFlying() || p.onGround() || p.isInWater() || !room(false)) {
			finish();
			return;
		}
		boolean cleared = GlideHold.cleared(this);
		boolean due = sinceStart >= INTERVAL || rocketWanted || stopForUse || GlideHold.exposed(this);
		// Not two starts in a row (Grim's ElytraC).
		if (cleared && due && sinceStart >= 2) {
			// Something to eat is waiting: this start puts the elytra back for good, and the swapping stops.
			if (stopForUse) finish();
			else restart(true);
		} else if (!cleared && sinceStart > INTERVAL + CLEAR_TIMEOUT) {
			finish();
		}
	}

	/**
	 * Altitude: the elytra stays on and the server glides you; before its glide reaches the 20 ticks that wear the elytra,
	 * a break. The chestplate goes on, so the server stops the glide (held, so you keep gliding and Grim doesn't hear of
	 * it yet), then the elytra goes back on, and once the server has it on, the glide starts again, which starts the
	 * server's count over. (On and the start in one tick, a start now and then went unanswered on 2b2t: as if the
	 * elytra weren't on yet.) A start
	 * the server doesn't answer (2b2t loses one now and then) leaves it not gliding you: after twice a round trip with no
	 * answer, it's started again (only then: a second start while the first is still on its way would be one while
	 * gliding, Grim's ElytraA).
	 */
	private void tickBreaks(LocalPlayer p) {
		boolean inAir = p.isFallFlying() && !p.onGround() && !p.isInWater() && !p.isPassenger();
		if (breaking) {
			sinceStart++;
			// About to land, or the stop never came: the elytra back on and the start now.
			if (!inAir || !room(false) || sinceStart > CLEAR_TIMEOUT) endBreak(true);
			// The stop is in: the elytra back on, the start once the server has it.
			else if (GlideHold.cleared(this)) endBreak(false);
			return;
		}
		if (equipping) {
			sinceStart++;
			// Not two starts in a row (Grim's ElytraC).
			if (!inAir || !room(false) || sinceStart > CLEAR_TIMEOUT || ChestSwap.elytraWorn() && ChestSwap.settled() && sinceStart >= 2) endBreak(true);
			return;
		}
		if (!inAir || !ChestSwap.elytraWorn()) {
			glidingFor = 0;
			unanswered = -1;
			return;
		}
		if (unanswered >= 0) {
			if (serverStarts != startsBefore || serverGliding) {
				unanswered = -1;
			} else if (++unanswered > 2 * roundTripTicks() + 4) {
				fly.info("The server didn't take a glide start: starting again");
				startAgain();
				return;
			} else {
				return;
			}
		}
		glidingFor++;
		// While you eat (or use anything held) the break waits: a swap would stop it (Grim), at the cost of a point of wear.
		if (glidingFor < BREAK_AFTER || p.isUsingItem() || !room(true) || !chestplateAtHand(true)) return;
		if (!ChestSwap.ready()) {
			warnOnce("No Durability can't swap armour with Curse of Binding.");
			return;
		}
		if (!GlideHold.arm(this)) return;
		ChestSwap.swap();
		breaking = true;
		sinceStart = 0;
	}

	/**
	 * Ends a break. Not {@code now}: the elytra goes on, and the start waits for the server to have it (see
	 * {@link #tickBreaks}). Now: the held answers out, the elytra on, and the glide started again unless the server still
	 * glides you.
	 */
	private void endBreak(boolean now) {
		breaking = false;
		glidingFor = 0;
		if (!now) {
			ChestSwap.restoreElytra();
			equipping = true;
			return;
		}
		equipping = false;
		LocalPlayer p = mc.player;
		boolean inAir = p.isFallFlying() && !p.onGround() && !p.isInWater();
		GlideHold.release(this);
		if (inAir && !serverGliding) {
			startAgain();
		} else {
			ChestSwap.restoreElytra();
		}
		GlideHold.disarm(this);
	}

	/** The elytra on (if it isn't) and a start, jump released the tick before as vanilla; its answer is watched for. */
	private void startAgain() {
		if (!ChestSwap.startGlide()) return;
		if (rocketWanted) fireRocket();
		startNow = true;
		glidingFor = 0;
		unanswered = 0;
		startsBefore = serverStarts;
	}

	/**
	 * Starts the glide again: the held ping answers go first (Grim sees the stop), the elytra goes on, the start is
	 * sent with jump pressed this tick and released the tick before (as vanilla starts one; Grim's ElytraB), a waiting
	 * rocket is used while the server takes it, and with {@code swapBack} the chestplate goes back on a couple of ticks
	 * later.
	 */
	private void restart(boolean swapBack) {
		GlideHold.release(this);
		if (!ChestSwap.startGlide()) {
			finish();
			return;
		}
		if (rocketWanted) fireRocket();
		startNow = true;
		sinceStart = 0;
		if (swapBack) ChestSwap.swap();
	}

	void finish() {
		finish(false);
	}

	/**
	 * Stops swapping, with the elytra back on and gliding again if the server stopped the glide. Never by dropping out of
	 * the glide in mid-air, which would land you with every block fallen since the server last glided: in the air it
	 * waits (unless {@code now}) for the server's stop for the last swap, which may still be on its way, and starts the
	 * glide again once it's in; it lets go without a start if none comes within a round trip (the elytra was back on in
	 * time, so the server never stopped).
	 */
	void finish(boolean now) {
		if (!swapping) return;
		LocalPlayer p = mc.player;
		boolean inAir = p.isFallFlying() && !p.onGround() && !p.isInWater();
		boolean cleared = GlideHold.cleared(this);
		if (inAir && !now && (!cleared || sinceStart < 2)) {
			if (!finishing) {
				finishing = true;
				finishWait = 0;
				ChestSwap.restoreElytra();
			}
			if (cleared || ++finishWait <= roundTripTicks() + 4) return;
		}
		swapping = finishing = false;
		armTicks = 0;
		if (inAir && cleared) {
			restart(false);
		} else {
			GlideHold.release(this);
			ChestSwap.restoreElytra();
			// The server had stopped the glide: so does the client, where you are.
			if (cleared && p.isFallFlying()) p.stopFallFlying();
		}
		GlideHold.disarm(this);
		rocketWanted = stopForUse = false;
	}

	/** Ticks for a packet to reach the server and its answer to come back, with a little slack. */
	private static int roundTripTicks() {
		return Mth.clamp(Myriad.server().ping() / 50 + 2, 2, 12);
	}

	/** While swapping: jump only as the press that starts the glide again (released the tick before). Same for a catch. */

	/** While swapping: jump only as the press that starts the glide again (released the tick before). Same for a catch. */
	void onInput(InputEvent e) {
		if (catching > 0) e.jump = catchNow;
		else if (swapping || breaking || equipping || unanswered >= 0 || startNow) e.jump = startNow;
	}


	// ---- catching a dropped glide -----------------------------------------------------------------------------------

	/** How long to keep trying to open the elytra again after the glide drops in mid-air. */
	private static final int CATCH_TICKS = 20;

	private boolean wasGliding, catchNow;
	/** Ticks left to catch a dropped glide (0: nothing to catch). */
	private int catching;

	/**
	 * A safety net: the glide stopping in mid-air with nothing a player would stop it for (no ground, water, ladder or
	 * vehicle) means the server's stop got through to the client (a setback, a lost hold, chunks that came in late), and
	 * a fall from there could cost every block the server counted while it wasn't gliding you. So the elytra is opened
	 * again at once, as a player would: on if a chestplate is worn, then jump released for a tick and pressed. Not when
	 * you'd land without hurting (Recast turned off mid-hop, a foot above the highway): opened there, the glide would
	 * skim the ground for the next 30 blocks, wearing the elytra, with No Durability's swapping kept off by the ground.
	 */
	private void catchGlide(LocalPlayer p) {
		boolean gliding = p.isFallFlying();
		boolean free = !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.onClimbable() && !p.getAbilities().flying;
		if (wasGliding && !gliding && free && !ElytraFly.bouncing() && !softLanding(p)) {
			catching = CATCH_TICKS;
			fly.info("Glide dropped in mid-air: opening the elytra again");
		}
		wasGliding = gliding;
		catchNow = false;
		if (catching == 0) return;
		if (gliding || !free) {
			catching = 0;
			return;
		}
		catching--;
		if (!ChestSwap.elytraWorn()) {
			ChestSwap.restoreElytra();
			return;
		}
		// Released last tick (the press opens it, as vanilla does), pressed this one.
		catchNow = !lastCatchPress;
		lastCatchPress = catchNow;
	}

	private boolean lastCatchPress;

	/**
	 * Whether you'd come down from here without fall damage: ground within the fall the server lets go (counting what
	 * it has already, and the rise still to come), under you and under where you're carried while you fall.
	 */
	private boolean softLanding(LocalPlayer p) {
		Vec3 v = p.getDeltaMovement();
		double rise = v.y > 0 ? v.y * v.y / (2 * 0.08) : 0;
		double drop = SAFE_DESCENT - 0.5 - descent - rise;
		if (drop <= 0) return false;
		AABB box = p.getBoundingBox();
		// Where you'll be over the next half second, taken at full speed (you slow down off the elytra, so that's farther).
		for (int t = 0; t <= 10; t += 2) {
			if (mc.level.noBlockCollision(p, box.move(v.x * t, 0, v.z * t).expandTowards(0, -drop, 0))) return false;
		}
		return true;
	}

	/**
	 * A rocket used meanwhile waits for the next start: the server only attaches rockets while it sees you gliding.
	 * Something you hold to use (food, a potion, a bow) waits for the swapping to stop: Grim stops whatever you're using
	 * each time you use another item (each swap) or change slot, so you'd never finish eating. It's used once the elytra
	 * is back on for good, at the next start (Auto Eat tries again, and holding right click uses it again).
	 */
	void onUse(InteractEvent.Item e) {
		if (!swapping && !breaking && !equipping || firing) return;
		ItemStack stack = mc.player.getItemInHand(e.hand());
		if (stack.is(Items.FIREWORK_ROCKET)) {
			e.cancel();
			rocketWanted = true;
			rocketHand = e.hand();
			rocketSlot = e.hand() == InteractionHand.MAIN_HAND ? Myriad.inventory().serverSlot() : -1;
		} else if (stack.getUseDuration(mc.player) > 0) {
			e.cancel();
			stopForUse = true;
		}
	}

	/** Uses the waiting rocket, from the hand or hotbar slot it was used from (or any rockets in the hotbar). */
	private void fireRocket() {
		rocketWanted = false;
		var inv = mc.player.getInventory();
		int slot = rocketSlot;
		if (rocketHand == InteractionHand.MAIN_HAND && (slot < 0 || !inv.getItem(slot).is(Items.FIREWORK_ROCKET))) slot = Myriad.inventory().findInHotbar(s -> s.is(Items.FIREWORK_ROCKET));
		boolean offHand = rocketHand == InteractionHand.OFF_HAND && mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET);
		if (!offHand && slot < 0) return;
		firing = true;
		try {
			if (offHand) mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
			else Myriad.inventory().silentSwap(slot, () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
		} finally {
			firing = false;
		}
	}

	/**
	 * Whether the chestplate (or, with it on, the elytra) is at hand to swap with, bringing it in from the inventory if
	 * not (see ChestSwap.ensurePair); says once why it isn't when it can't be.
	 */
	boolean chestplateAtHand(boolean mayStop) {
		NoDurability m = this;
		switch (ChestSwap.ensurePair(mayStop)) {
			case READY -> {
				m.pairWarned = false;
				return true;
			}
			case MISSING -> m.pairWarning(ChestSwap.elytraWorn() ? "No Durability needs a chestplate in your inventory." : "No Durability can't find the elytra to put back on.");
			case NO_ROOM -> m.pairWarning("No Durability needs a free hotbar slot (or one with a spare elytra) for the chestplate.");
			case WAITING -> {
			}
		}
		return false;
	}

	private void pairWarning(String message) {
		if (!pairWarned) fly.warn(message);
		pairWarned = true;
	}

	private void warnOnce(String message) {
		if (!warned) fly.warn(message);
		warned = true;
	}

	/**
	 * Keeps {@link #descent} as the server counts it: every block down adds, rising clears it, as does landing, and so
	 * does gliding (not swapping) long enough at a gentle slope.
	 */
	private void trackDescent(LocalPlayer p) {
		double dy = p.getY() - p.yo;
		if (dy > 0 || p.onGround() || p.isInWater()) descent = 0;
		else descent -= dy;
		glidingTicks = swapping ? 0 : glidingTicks + 1;
		if (!swapping && glidingTicks >= FORGET_TICKS && p.getDeltaMovement().y > -0.5) descent = 0;
	}

	/**
	 * Whether there's room enough that the server won't see you land, or hit something, while it isn't gliding you:
	 * a few blocks below plus how far you fall (or rise) in a round trip, and nothing along the way you're flying for
	 * that long (rising ground, a wall, a tree). Swapping stops when there isn't, in time for the elytra to be back on
	 * and the server gliding first.
	 */
	private boolean room(boolean arming) {
		LocalPlayer p = mc.player;
		Vec3 v = p.getDeltaMovement();
		int roundTrip = roundTripTicks();
		// Once the server counts a fall that would hurt, it needs time to forget it before you touch anything: for the
		// last swap's stop to come in, the glide to start again, and the server to glide a while.
		double ticks = roundTrip + 4 + (arming ? 2 : 0) + (descent > SAFE_DESCENT ? roundTrip + FORGET_TICKS : 0);
		double need = 6 + (v.y < 0 ? -v.y * ticks : v.y * (roundTrip / 2.0 + 2)) + (arming ? 2 : 0);
		AABB box = p.getBoundingBox();
		// Chunks you'd reach that haven't come in: the client stops you there until they do, which shouldn't happen
		// while the server isn't gliding you.
		for (double t = 0; t <= ticks; t += 4) {
			if (!mc.level.hasChunk(Mth.floor(p.getX() + v.x * t) >> 4, Mth.floor(p.getZ() + v.z * t) >> 4)) return false;
		}
		if (!mc.level.noBlockCollision(p, box.expandTowards(0, -need, 0))) return false;
		// Along the way: the whole hitbox swept to where you'll be, and a couple of blocks under that path.
		return mc.level.noBlockCollision(p, box.expandTowards(v.x * ticks, Math.min(0, v.y * ticks) - 2, v.z * ticks));
	}
}
