package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps your elytra from wearing out (core's {@link NoDurability}): where the server simulates the glide (Grim, 2b2t)
 * it swaps the elytra for a chestplate from your inventory while you glide and back for a moment every few ticks, so
 * the server never glides you long enough to wear it; elsewhere it breaks the glide briefly instead. It also opens the
 * elytra again if the glide drops in mid-air for no reason.
 * <p>
 * It works for whoever flies: {@link ElytraFly}, whose types ask it through {@link #behaviourFor}, and with When You
 * Fly, you (rockets, Rocket Boost). Put it in a control panel with Elytra Fly for a flight that keeps the elytra, or leave it off
 * to let it wear.
 * <p>
 * For addon developers: {@link #BEHAVIOUR} decides, per flight and per server, whether it swaps and how. Its caller
 * is the flight asking (Elytra Fly's types have ids, {@code myriad-essentials:recast}, {@code :altitude},
 * {@code :control}), so an addon can say how one flight should swap on one server:
 *
 * <pre>{@code
 * ctx.hook(ElytraSwap.BEHAVIOUR)
 *     .named("Recast keeps its elytra under NoCheatPlus")
 *     .when(Match.caller("myriad-essentials:recast").on(AntiCheat.Profile.NCP))
 *     .handle(call -> call.input().withSwaps(false));
 * }</pre>
 */
public class ElytraSwap extends Module implements NoDurability.Owner {
	/**
	 * How Elytra Swap works for one flight: whether it swaps at all, and whether each start of the glide waits for the
	 * server to have the elytra back on (long fast glides, where 2b2t now and then ignored a start sent with the swap).
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
	 * How Elytra Swap works for a flight: the input is what the flight asks for (it swaps while the module is on), the
	 * caller is the flight.
	 */
	public static final Hook<Behaviour, Behaviour> BEHAVIOUR = Hook.of("myriad-essentials:elytra_swap", "Elytra Swap",
		"Whether Elytra Swap swaps for a flight, and whether each glide start waits for the elytra.");

	private final BoolSetting whenYouFly = sgGeneral.bool("When You Fly")
		.description("Swap while you fly yourself (rockets, Rocket Boost) too, not only for Elytra Fly.").defaultValue(true).build();

	/** The swapping for when you fly yourself; Elytra Fly's types each run their own. */
	private final NoDurability noDur = Myriad.elytra().noDurability(this);
	private boolean running;

	public ElytraSwap() {
		super(Tags.MOVEMENT, "Elytra Swap", "Keep your elytra from wearing out while you glide.");
	}

	/**
	 * How Elytra Swap works for {@code flight}: swapping while the module is on, waiting for the elytra before each
	 * start if {@code equipBeforeStart}, unless a {@link #BEHAVIOUR} hook says otherwise.
	 */
	public static Behaviour behaviourFor(Object flight, boolean equipBeforeStart) {
		Behaviour asked = new Behaviour(Modules.isActive(ElytraSwap.class), equipBeforeStart);
		if (!BEHAVIOUR.hooked()) return asked;
		Behaviour hooked = BEHAVIOUR.call(flight, asked, b -> b);
		return hooked != null ? hooked : asked;
	}

	/**
	 * Takes over a setting from before Elytra Swap was a module of its own (Elytra Fly's No Durability, Auto Armor's No
	 * Durability Elytra), from their settings migrations: turned on if {@code on}, and When You Fly set if given. Those
	 * modules load after this one (see Essentials), so this one's own saved state is already in.
	 */
	public static void adoptFormerSetting(boolean on, @Nullable Boolean whenYouFly) {
		ElytraSwap swap = Modules.get(ElytraSwap.class);
		if (swap == null) return;
		if (on) swap.setEnabledSilently(true);
		if (whenYouFly != null) swap.whenYouFly.set(whenYouFly);
	}

	/** Whether it swaps when you fly yourself: on, with When You Fly, and no Elytra Fly flying. */
	public boolean swapsForYou() {
		return isEnabled() && whenYouFly.get() && !ElytraFlight.flightModuleOn() && noDurability();
	}

	/** Whether it's swapping for you (no Elytra Fly flying) right now. */
	public boolean swappingForYou() {
		return running && noDur.swapping();
	}

	@Override
	protected void onDisable() {
		stop();
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
}
