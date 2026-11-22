package dev.myriadclient.essentials.modules.movement.elytra;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.KeyEvent;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;

/**
 * Elytra flight made easier, whoever flies:
 * <ul>
 * <li><b>No Durability</b> keeps your elytra from wearing out (core's {@link NoDurability}): where the server simulates
 * the glide (Grim, 2b2t) it swaps the elytra for a chestplate from your inventory while you glide and back for a moment
 * every few ticks, so the server never glides you long enough to wear it; elsewhere it breaks the glide briefly instead.
 * It also opens the elytra again if the glide drops in mid-air for no reason.</li>
 * <li><b>Glide Bind</b> starts the glide the first moment the server allows one (off the ground and out of water): at
 * once in the air, with one jump from the ground where there's room, or as you come out of water or drop out of a gap,
 * without timing a double jump.</li>
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
	private final KeybindSetting glideBind = sgGeneral.keybind("Glide Bind").description("Start gliding the first moment the server allows "
		+ "(off the ground, out of water): at once in the air, one jump from the ground where there's room, or as you come out of water.").build();

	/** The swapping for when you fly yourself; Elytra Fly's types each run their own. */
	private final NoDurability noDur = Myriad.elytra().noDurability(this);
	private boolean running;

	public ElytraTweaks() {
		super(Tags.MOVEMENT, "Elytra Tweaks", "No Durability and a bind to start the glide.");
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
	/**
	 * Ticks the bind stays armed for a glide that can't start yet: the server only starts one off the ground and out of
	 * water, so in water, or on the ground with no room to jump (a gap you crawl through), it starts the tick you're out.
	 */
	private int armed;
	private static final int ARMED_TICKS = 100;

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
		armed = ARMED_TICKS;
		tryGlide();
	}

	/**
	 * Starts the glide if it can now: in the air, the jump key let go then pressed; on the ground with room above, a jump
	 * first. In water (the server won't start a glide there) or on the ground with no room to jump, it stays armed.
	 */
	private void tryGlide() {
		var p = mc.player;
		if (armed <= 0 || !jumps.isEmpty() || p.isFallFlying()) return;
		if (p.isInLiquid()) return;
		if (!p.onGround()) jumps.addAll(java.util.List.of(false, true));
		else if (roomToJump()) jumps.addAll(java.util.List.of(true, false, true));
		else return;
		armed = 0;
		glideWait = GLIDE_WAIT_TICKS;
	}

	/** Whether a jump would lift you: no block right above your head (a one-block gap has none). */
	private boolean roomToJump() {
		return mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, 0.5, 0));
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

	/** An armed bind starts the glide once it can; one started that didn't take in time (no elytra worn, landed) is dropped. */
	private void startGlide() {
		if (mc.player.isFallFlying()) {
			armed = 0;
			jumps.clear();
			return;
		}
		if (armed > 0) {
			armed--;
			tryGlide();
		}
		if (!jumps.isEmpty() && --glideWait < 0) jumps.clear();
	}
}
