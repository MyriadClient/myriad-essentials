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
 * you could land or hit anything. The server's equip sound for each swap is muted.
 * <p>
 * Every restart is checked: the server answers one by gliding you for a moment. When no answer comes (2b2t sometimes
 * drops a start), the server isn't gliding you while you glide, and Grim would set you back until it does: so the
 * elytra goes back on for good and the glide is started again at once, as every restart does, and swapping only picks
 * up again after a while of gliding the server agrees with. If the server keeps refusing, Elytra Fly is told it
 * can't go on (Altitude lands). Eating (or anything you hold to use) pauses the swapping: Grim stops what
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
	 * Ticks of gliding the server needs, elytra back on, before it lets go of that count: it only does while it sees you
	 * descend gently, and its idea of your speed (a free fall while it wasn't gliding you) takes a while to settle.
	 */
	private static final int FORGET_TICKS = 30;
	/** After a refused restart: ticks of plain gliding before swapping again; and refusals in a row before giving up. */
	private static final int REST_TICKS = 100, MAX_REFUSALS = 3;
	/** Vanilla's gliding bit in the entity flag byte. */
	private static final int GLIDING_BIT = 1 << 7;

	/**
	 * Network thread: the server's glide as your entity flags say, and how many times it has started (the bit set after
	 * being clear).
	 */
	private static volatile boolean serverGliding;
	private static volatile int serverStarts;

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
	/** A start went out and the server hasn't glided you since: ticks since it went, and the server's starts before it. */
	private boolean awaitingStart;
	private int sinceRestart, startsBefore;
	/** Making good a refused start: the elytra is on for good and its start out. */
	private boolean recovering;
	private int refusals, restUntil;

	void enable() {
		swapping = startNow = rocketWanted = stopForUse = finishing = awaitingStart = false;
		recovering = false;
		refusals = restUntil = 0;
		descent = 0;
		armTicks = catching = 0;
	}

	void disable() {
		if (mc.player != null && mc.level != null) {
			// No next tick to wait for: start the glide again now rather than drop out of it in mid-air.
			finish(true);
			// Turning off mid-swap can start the glide again, and Elytra Fly no longer hears the input event that presses
			// jump with it (Grim's ElytraB): press it from a listener of its own, for that one tick.
			if (startNow) pressJumpOnce();
		}
		swapping = startNow = awaitingStart = false;
		recovering = false;
		catching = 0;
		GlideHold.disarm(this);
	}

	/** Whether the elytra and chestplate are being swapped (or the elytra put back for good, or a refused start made good) right now. */
	boolean swapping() {
		return swapping || recovering;
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

	/** Network thread, from Elytra Fly: the local player's entity flags, as the server sent them. */
	static void onOwnFlags(byte flags) {
		boolean gliding = (flags & GLIDING_BIT) != 0;
		if (gliding && !serverGliding) serverStarts++;
		serverGliding = gliding;
	}

	private void tickNoDurability() {
		LocalPlayer p = mc.player;
		trackDescent(p);
		if (recovering || awaitingStart) {
			if (!checkStart(p)) return;
		}
		if (!ElytraFly.noDurability()) {
			finish();
			return;
		}
		if (!swapping) {
			// Elytra Fly's bounce does its own swapping.
			// Not while you eat (or use anything held): a swap would stop it (see onUse).
			boolean ready = p.isFallFlying() && ChestSwap.elytraWorn() && !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.isUsingItem()
				&& !ElytraFly.holdsGlide() && p.tickCount >= restUntil && room(true);
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
		expectStart();
		if (swapBack) ChestSwap.swap();
	}

	private void expectStart() {
		awaitingStart = true;
		sinceRestart = 0;
		startsBefore = serverStarts;
	}

	/**
	 * Watches the last start: the server answers one it takes by gliding you, within a round trip; with no answer it
	 * didn't, and isn't gliding you while you glide. False while making good a refused one (nothing else runs then).
	 */
	private boolean checkStart(LocalPlayer p) {
		boolean inAir = p.isFallFlying() && !p.onGround() && !p.isInWater() && !p.isPassenger();
		if (!inAir || !awaitingStart) {
			awaitingStart = false;
			recovering = false;
			return true;
		}
		// While swapping, the answer is a new glide; making good a refused start, the server gliding at all.
		if (serverStarts != startsBefore || recovering && serverGliding) {
			awaitingStart = false;
			if (recovering) {
				recovering = false;
				fly.info("The server glides you again: No Durability picks up again shortly");
			} else {
				refusals = 0;
			}
			return true;
		}
		if (++sinceRestart <= roundTripTicks() + 2) return !recovering;
		// Close to the ground the landing (see finish) has it in hand: no second start there.
		if (!room(false)) {
			awaitingStart = false;
			recovering = false;
			return true;
		}
		refused(p);
		return false;
	}

	/**
	 * The server didn't take a start: stop swapping without waiting for a stop that won't come, and start the glide
	 * again at once with the elytra on for good (on and the start in one tick, as every restart goes).
	 */
	private void refused(LocalPlayer p) {
		awaitingStart = false;
		refusals++;
		GlideHold.release(this);
		GlideHold.disarm(this);
		swapping = finishing = false;
		armTicks = 0;
		rocketWanted = stopForUse = false;
		restUntil = p.tickCount + REST_TICKS;
		if (refusals > MAX_REFUSALS || !ChestSwap.startGlide()) {
			recovering = false;
			ChestSwap.restoreElytra();
			fly.cannotGoOn("the server keeps refusing to glide you");
			return;
		}
		fly.info("The server didn't take a glide restart: elytra back on until it glides you again");
		startNow = true;
		recovering = true;
		expectStart();
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
		else if (swapping || startNow || recovering) e.jump = startNow;
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
		if (!swapping || firing) return;
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
