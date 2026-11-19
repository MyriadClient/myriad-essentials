package dev.myriadclient.essentials.modules.movement.elytra;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.myriadclient.api.event.events.KeyEvent;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.KeybindSetting;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.BoolSetting;

/**
 * Elytra flight made easier, whoever flies:
 * <ul>
 * <li><b>No Durability</b> keeps your elytra from wearing out (core's {@link NoDurability}): where the server simulates
 * the glide (Grim, 2b2t) it swaps the elytra for a chestplate from your inventory while you glide and back for a moment
 * every few ticks, so the server never glides you long enough to wear it; elsewhere it breaks the glide briefly instead.
 * It also opens the elytra again if the glide drops in mid-air for no reason.</li>
 * <li><b>Air Brake</b>: gliding at a wall fast enough for the hit to hurt, within Brake Distance of it (3 blocks, so the
 * rest of the flight is left alone), the server sees you look back the way you're
 * moving (your view stays yours, your up and down angle too), so the glide, a rocket's push included, takes the speed
 * off whichever way you fly, until it's safe.</li>
 * <li><b>Glide Bind</b> starts the glide: at once in the air, or with one jump from the ground and the glide straight
 * after, without timing a double jump.</li>
 * </ul>
 * <p>
 * It works for whoever flies: {@link ElytraFly}, whose types ask it through {@link #behaviourFor}, and with When You
 * Fly, you (rockets, Rocket Boost). Put it in a control panel with Elytra Fly for a flight that keeps the elytra, or
 * leave it off to let it wear.
 * <p>
 * For addon developers: {@link #BEHAVIOUR} decides, per flight and per server, whether it swaps and how. Its caller
 * is the flight asking (Elytra Fly's types have ids, {@code myriad-essentials:recast}, {@code :altitude},
 * {@code :control}), so an addon can say how one flight should swap on one server:
 *
 * <pre>{@code
 * ctx.hook(ElytraTweaks.BEHAVIOUR)
 *     .named("Recast keeps its elytra under NoCheatPlus")
 *     .when(Match.caller("myriad-essentials:recast").on(AntiCheat.Profile.NCP))
 *     .handle(call -> call.input().withSwaps(false));
 * }</pre>
 */
public class ElytraTweaks extends Module implements NoDurability.Owner {
	/**
	 * How Elytra Tweaks works for one flight: whether it swaps at all, and whether each start of the glide waits for the
	 * server to have the elytra back on (long fast glides, where 2b2t now and then ignores a start sent with the swap).
	 */
	public record Behaviour(boolean swaps, boolean equipBeforeStart) {
		public Behaviour withSwaps(boolean swaps) {
			return new Behaviour(swaps, equipBeforeStart);
		}

		public Behaviour withEquipBeforeStart(boolean equipBeforeStart) {
			return new Behaviour(swaps, equipBeforeStart);
		}
	}

	/**
	 * How Elytra Tweaks works for a flight: the input is what the flight asks for (it swaps while the module is on), the
	 * caller is the flight.
	 */
	public static final Hook<Behaviour, Behaviour> BEHAVIOUR = Hook.of("myriad-essentials:elytra_tweaks", "Elytra Tweaks",
		"Whether Elytra Tweaks swaps for a flight, and whether each glide start waits for the elytra.");

	private final BoolSetting noDurabilitySetting = sgGeneral.bool("No Durability").description("Keep the elytra from wearing out, whoever "
		+ "flies (you, or Elytra Fly): swapped for a chestplate while you glide where the server simulates the glide, broken briefly elsewhere.")
		.defaultValue(true).build();
	private final BoolSetting airBrake = sgGeneral.bool("Air Brake").description("Gliding at a wall fast enough for the hit to hurt, the server "
		+ "sees you look back the way you came (your view stays yours, the up and down angle too) until it's safe: the glide, or a rocket's push, "
		+ "takes the speed off whichever way you fly. Not while Elytra Fly flies.").build();
	private final DoubleSetting brakeDistance = sgGeneral.decimal("Brake Distance").description("How close the wall has to be before Air "
		+ "Brake comes on, in blocks. Closer leaves your flight alone until the last moment; further takes more of a fast hit off.")
		.defaultValue(3).range(1, 16).decimals(1).visible(airBrake::get).build();
	private final KeybindSetting glideBind = sgGeneral.keybind("Glide Bind").description("Start gliding: at once in the air, or one jump from "
		+ "the ground and the glide straight after.").build();

	/** The swapping for when you fly yourself; Elytra Fly's types each run their own. */
	private final NoDurability noDur = Myriad.elytra().noDurability(this);
	private boolean running;

	public ElytraTweaks() {
		super(Tags.MOVEMENT, "Elytra Tweaks", "No Durability, Air Brake and a bind to start the glide.");
		formerNames("Elytra Swap");
	}

	/**
	 * How Elytra Tweaks works for {@code flight}: swapping while the module is on, waiting for the elytra before each
	 * start if {@code equipBeforeStart}, unless a {@link #BEHAVIOUR} hook says otherwise.
	 */
	public static Behaviour behaviourFor(Object flight, boolean equipBeforeStart) {
		ElytraTweaks tweaks = Modules.active(ElytraTweaks.class);
		Behaviour asked = new Behaviour(tweaks != null && tweaks.noDurabilitySetting.get(), equipBeforeStart);
		if (!BEHAVIOUR.hooked()) return asked;
		Behaviour hooked = BEHAVIOUR.call(flight, asked, b -> b);
		return hooked != null ? hooked : asked;
	}

	/**
	 * Takes over a setting older configs keep in another module (Elytra Fly's No Durability, Auto Armor's No Durability
	 * Elytra), from their settings migrations: turned on if {@code on}. Those modules load after this one (see
	 * Essentials), so this one's own saved state is already in.
	 */
	public static void adoptFormerSetting(boolean on) {
		ElytraTweaks tweaks = Modules.get(ElytraTweaks.class);
		if (tweaks != null && on) tweaks.setEnabledSilently(true);
	}

	/** Whether it swaps for you flying yourself: on, with No Durability, and no Elytra Fly flying (its types run their own). */
	public boolean swapsForYou() {
		return isEnabled() && !ElytraFlight.flightModuleOn() && noDurability();
	}

	/** Whether it's swapping for you (no Elytra Fly flying) right now. */
	public boolean swappingForYou() {
		return running && noDur.swapping();
	}

	@Override
	protected void onDisable() {
		stop();
		braking = releasing = false;
		brakeHold = 0;
		if (piloting) Myriad.elytra().removePilot(brakePilot);
		piloting = false;
		jumps.clear();
	}

	@Override
	public boolean noDurability() {
		return behaviourFor(this, false).swaps();
	}

	@Override
	public boolean equipBeforeStart() {
		return behaviourFor(this, false).equipBeforeStart();
	}

	/** Runs the swapping for you while no Elytra Fly type flies: with the flights' own, before the tick's actions. */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 10)
	private void onTick(TickEvent.Pre e) {
		if (inGame()) {
			startGlide();
			brake();
		}
		if (!inGame() || !swapsForYou()) {
			stop();
			return;
		}
		if (!running) {
			noDur.enable();
			running = true;
		}
		noDur.tick();
	}

	private void stop() {
		if (!running) return;
		running = false;
		noDur.disable();
	}

	// ---- Glide Bind ---------------------------------------------------------------------------------------------

	/**
	 * The jump key, tick by tick, that starts the glide the way vanilla does (jump pressed in the air, released the tick
	 * before, as Grim's ElytraB checks): in the air release then press; on the ground press (the jump), release, press.
	 * Empty when there's nothing to do.
	 */
	private final java.util.ArrayDeque<Boolean> jumps = new java.util.ArrayDeque<>();
	/** Ticks the glide is waited for after the bind, the elytra worn and you off the ground. */
	private int glideWait;
	private static final int GLIDE_WAIT_TICKS = 12;

	@Subscribe
	private void onKey(KeyEvent e) {
		if (glideBind.wasPressed(e)) glidePressed();
	}

	@Subscribe
	private void onMouse(MouseButtonEvent e) {
		if (glideBind.wasPressed(e)) glidePressed();
	}

	private void glidePressed() {
		if (!inGame() || mc.player.isFallFlying() || !jumps.isEmpty()) return;
		if (mc.player.onGround()) jumps.addAll(java.util.List.of(true, false, true));
		else jumps.addAll(java.util.List.of(false, true));
		glideWait = GLIDE_WAIT_TICKS;
	}

	/** The jump key for this tick while a glide is being started. */
	@Subscribe
	private void onInput(InputEvent e) {
		if (jumps.isEmpty() || !inGame()) return;
		var p = mc.player;
		// The press that starts the glide waits until you're off the ground (the jump takes a tick to lift you).
		if (jumps.size() == 1 && p.onGround()) {
			e.jump = false;
			return;
		}
		e.jump = jumps.poll();
	}

	/** Gives up on a glide that didn't start in time (no elytra worn, landed, in water). */
	private void startGlide() {
		if (jumps.isEmpty()) return;
		if (mc.player.isFallFlying() || --glideWait < 0) jumps.clear();
	}

	// ---- Air Brake ----------------------------------------------------------------------------------------------

	/**
	 * Fastest a glide may hit a wall without hurting, in blocks a tick: the hit hurts by ten times the speed it loses,
	 * less three.
	 */
	private static final double SAFE_SPEED = 0.28;
	/** The yaw the server gets while braking: back along the motion across as it was when the brake began. */
	private float brakeYaw;
	/** Step, in blocks, the path ahead is checked at for a wall. */
	private static final double LOOK_STEP = 0.25;
	/** Ticks the brake holds after the danger is gone, so it doesn't flick on and off. */
	private static final int BRAKE_HOLD_TICKS = 4;
	private boolean braking, releasing, piloting;
	private int brakeHold;
	/** Steers the glide by the look sent while it brakes. */
	private final Elytra.Pilot brakePilot = new Elytra.Pilot() {
		@Override
		public boolean steers() {
			return true;
		}
	};

	/** While you glide yourself, looks up for the server before a wall you'd hit fast enough to be hurt. */
	private void brake() {
		var p = mc.player;
		boolean danger = airBrake.get() && p.isFallFlying() && !ElytraFlight.flightModuleOn() && wallAhead();
		if (danger) brakeHold = BRAKE_HOLD_TICKS;
		else if (brakeHold > 0) brakeHold--;
		// A rocket still pushing holds the brake till it burns out: let go sooner, it pushed you back at the wall along your
		// own look, the brake came on again, and the look swung back and forth.
		boolean rocket = p.tickCount < rocketUntil;
		if (danger || braking && (brakeHold > 0 || rocket) && p.isFallFlying()) {
			releasing = false;
			if (!braking) {
				// Back the way you're moving across, your up and down angle kept: the glide turns its speed towards the look
				// across, so looking back takes the speed off (a rocket's push too), whichever way you fly. Fixed for the
				// brake: worked out again each tick, it swung round with the motion (a rocket can turn it back), the look
				// jumping half a turn in a tick, which Grim's simulation and the glide took differently.
				Vec3 v = p.getDeltaMovement();
				brakeYaw = (float) Math.toDegrees(Math.atan2(v.x, -v.z));
				if (!piloting) Myriad.elytra().addPilot(brakePilot);
				piloting = braking = true;
			}
			Myriad.rotations().request(this, brakeYaw, p.getXRot(), Rotations.PRIORITY_HIGH, Rotations.Options.INSTANT, null);
		} else {
			endBrake();
		}
	}

	/**
	 * Ends the brake. The glide keeps following the look sent to the server until that's back on your own: let go at
	 * once, the glide took your look a tick before the server had it again (Grim, Simulation).
	 */
	private void endBrake() {
		if (braking) {
			braking = false;
			brakeHold = 0;
			releasing = true;
		}
		if (!releasing || !piloting) return;
		float[] sent = Myriad.rotations().rotationForAction();
		var p = mc.player;
		if (p == null || Math.abs(Mth.wrapDegrees(sent[0] - p.getYRot())) < 0.5f && Math.abs(sent[1] - p.getXRot()) < 0.5f) {
			Myriad.elytra().removePilot(brakePilot);
			piloting = releasing = false;
		}
	}

	/** Until when (ticks) a rocket you used while gliding still pushes. */
	private int rocketUntil;

	@Subscribe(packets = ServerboundUseItemPacket.class)
	private void onRocket(PacketEvent.Send e) {
		if (!(e.packet() instanceof ServerboundUseItemPacket use) || mc.player == null || !mc.player.isFallFlying()) return;
		ItemStack stack = mc.player.getItemInHand(use.hand());
		if (!stack.is(Items.FIREWORK_ROCKET)) return;
		var fireworks = stack.get(DataComponents.FIREWORKS);
		// A rocket's life: ten ticks a flight (one more than it says), and up to twelve at random.
		rocketUntil = mc.player.tickCount + 10 * ((fireworks != null ? fireworks.flightDuration() : 1) + 1) + 12;
	}

	/**
	 * Whether gliding on as you are would run you into a wall fast enough to hurt, within Brake Distance (or the next
	 * tick, if that goes further): your box carried along your motion meets a block your motion across (not down) runs into.
	 */
	private boolean wallAhead() {
		var p = mc.player;
		Vec3 v = p.getDeltaMovement();
		double speed = v.horizontalDistance();
		if (speed <= SAFE_SPEED) return false;
		AABB box = p.getBoundingBox();
		double reach = Math.max(brakeDistance.get(), speed);
		for (double d = LOOK_STEP; d < reach + LOOK_STEP / 2; d += LOOK_STEP) {
			double t = Math.min(d, reach) / speed;
			if (mc.level.noCollision(p, box.move(v.x * t, v.y * t, v.z * t))) continue;
			// The ground under the glide isn't a wall: only a hit the motion across makes.
			return !mc.level.noCollision(p, box.move(v.x * t, Math.max(0, v.y * t), v.z * t)) || mc.level.noCollision(p, box.move(0, v.y * t, 0));
		}
		return false;
	}
}
