package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.DoubleSetting;

import java.util.List;

/**
 * Flies an elytra, three ways (Type): {@link Recast} bounces along highways, {@link Altitude} crosses open country
 * holding its height without fireworks, and {@link ElytraControl} flies with the movement keys like a creative flight.
 * Control is only offered where the server's anti-cheat doesn't simulate the glide (6b6t's NoCheatPlus, singleplayer):
 * Grim (2b2t) sets it back from the first tick, so there Type lists only Recast and Altitude, and Control chosen
 * elsewhere waits. No Durability and Preserve are shared (see {@link ElytraMode}); No Durability works the way the
 * server allows (see {@link NoDurability}). Only the chosen type's own settings show.
 */
public class ElytraFly extends Module {
	static final String RECAST = "Recast", ALTITUDE = "Altitude", CONTROL = "Control";

	private final ChoiceSetting type = sgGeneral.choice("Type", ElytraFly::types)
		.description("Recast: bounce along highways. Altitude: cross open country, holding your height. Control: fly with the movement keys (only where the server checks elytras loosely, such as 6b6t).")
		.defaultValue(RECAST).onChanged(t -> switchType()).build();
	private final BoolSetting noDurability = sgGeneral.bool("No Durability")
		.description("Keep the elytra from wearing out while you glide: with a chestplate from your inventory on 2b2t and servers like it, by stopping and starting the glide elsewhere.")
		.defaultValue(true).build();
	private final DoubleSetting preserve = sgGeneral.doubleSetting("Preserve")
		.description("Put elytras with less durability than this (%) aside, and fly with another.").defaultValue(5).range(0, 50).decimals(0).build();
	private final BoolSetting lockHeading = sgGeneral.bool("Lock Heading")
		.description("Keep flying the way you faced when the glide started, whatever the camera does (to any angle, not snapped). Until you land or turn it off.")
		.defaultValue(false).visible(() -> type.get().equals(ALTITUDE) || type.get().equals(CONTROL)).build();

	final Recast recast = new Recast(this);
	final Altitude altitude = new Altitude(this);
	final ElytraControl control = new ElytraControl(this);
	/** The type flying (null while off, or while Control is chosen where it doesn't work). */
	private ElytraMode flying;
	private boolean unavailableWarned;

	public ElytraFly() {
		super(Categories.MOVEMENT, "Elytra Fly", "Fly an elytra: bounce along highways, cross open country, or fly with the movement keys.");
		// The modules it was before (Recast's settings, keybind and on/off state carry over; it was Elytra Fly before that).
		formerNames("Recast", "Altitude", "Elytra Control");
	}

	/** The types offered on this server. */
	private static List<String> types() {
		return AntiCheat.strict() ? List.of(RECAST, ALTITUDE) : List.of(RECAST, ALTITUDE, CONTROL);
	}

	/** The type chosen. */
	String typeName() {
		return type.get();
	}

	/** The type flying, or null. */
	ElytraMode flying() {
		return flying;
	}

	boolean noDurability() {
		return noDurability.get();
	}

	double preserve() {
		return preserve.get();
	}

	/** Altitude and Control: fly the heading faced when the glide started. */
	boolean lockHeading() {
		return lockHeading.get();
	}

	/** The type that should fly now: the chosen one, unless it's Control on a server that sets it back. */
	private ElytraMode wanted() {
		return switch (type.get()) {
			case ALTITUDE -> altitude;
			case CONTROL -> AntiCheat.strict() ? null : control;
			default -> recast;
		};
	}

	@Override
	protected void onEnable() {
		unavailableWarned = false;
		switchType();
	}

	@Override
	protected void onDisable() {
		if (flying != null) flying.stop();
		flying = null;
	}

	/** Before the types' own tick: the server's anti-cheat can become known after you join (Control stops then). */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 20)
	private void onTick(TickEvent.Pre e) {
		if (wanted() != flying) switchType();
	}

	private void switchType() {
		if (!isEnabled()) return;
		ElytraMode next = wanted();
		if (next == flying) return;
		if (flying != null) flying.stop();
		flying = next;
		if (next != null) {
			unavailableWarned = false;
			next.start();
		} else if (!unavailableWarned) {
			unavailableWarned = true;
			warn("Control doesn't fly on this server (its anti-cheat simulates the glide): use Recast or Altitude here.");
		}
	}

	@Override
	public String hudInfo() {
		if (flying == null) return "Unavailable";
		String info = flying.hudInfo();
		return info == null ? flying.name() : flying.name() + " " + info;
	}
}
