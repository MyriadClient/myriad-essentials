package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.Flight;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;

import java.util.function.Function;

/**
 * A type that is settings over one of core's flights ({@link Altitude}, {@link ElytraControl}): core flies it, with No
 * Durability as {@link ElytraSwap} says, Preserve, the catch, slowing for chunks and riding out setbacks; the type
 * steers it with the camera or {@link RotationLock}'s heading each tick.
 */
abstract class FlightMode extends ElytraMode {
	protected final Flight flight;

	protected FlightMode(ElytraFly fly, String name, Function<NoDurability.Owner, Flight> flight) {
		super(fly, name);
		this.flight = flight.apply(this);
	}

	@Override
	protected void onEnable() {
		super.onEnable();
		flight.start();
	}

	@Override
	protected void onDisable() {
		flight.stop();
	}

	/** Before the flight's own tick: where to fly this tick. */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 15)
	private void onTick(TickEvent.Pre e) {
		if (!inGame()) return;
		flight.preserve(fly.preserve());
		steer();
	}

	/** Sets this tick's heading (and whatever else the type sets). */
	protected abstract void steer();

	@Override
	public String hudInfo() {
		return flight.status();
	}

	/** A swap of the flight's No Durability the server hasn't answered yet (off the ground, Elytra Flight says so anyway). */
	@Override
	boolean holdsChest() {
		return !Myriad.elytra().chestSwap().settled();
	}
}
