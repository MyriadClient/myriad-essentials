package dev.myriadclient.essentials.modules.movement;

import com.google.gson.JsonElement;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SavedSettings;

import java.util.List;

/**
 * Flies an elytra, three ways (Type): {@link Recast} bounces along highways, {@link Altitude} crosses open country
 * holding its height without fireworks, and {@link ElytraControl} flies with the movement keys like a creative flight.
 * Control is only offered where the server's anti-cheat doesn't simulate the glide (6b6t's NoCheatPlus, singleplayer):
 * Grim (2b2t) sets it back from the first tick, so there Type lists only Recast and Altitude, and Control chosen
 * elsewhere waits. Preserve is shared by the types (see {@link ElytraMode}). Two modules of their own go with it,
 * often in one control panel: {@link ElytraSwap} keeps the elytra from wearing out, the way the server allows, and
 * {@link LockRotation} holds the heading for Altitude and Control. Snap rounds the way each type flies (Recast's lane,
 * a locked heading) to 45° or 90°, kept for each type. Only the chosen type's own settings show.
 */
public class ElytraFly extends Module {
	/** The types, as Type takes them. */
	public static final String RECAST = "Recast", ALTITUDE = "Altitude", CONTROL = "Control";

	/** What a locked heading is rounded to. */
	public enum Snap {
		OFF("Off", 0), DEGREES_45("45°", 45), DEGREES_90("90°", 90);

		private final String label;
		private final int degrees;

		Snap(String label, int degrees) {
			this.label = label;
			this.degrees = degrees;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public final ChoiceSetting type = sgGeneral.choice("Type", ElytraFly::types)
		.description("Recast: bounce along highways. Altitude: cross open country, holding your height. Control: fly with the movement keys (only where the server checks elytras loosely, such as 6b6t).")
		.defaultValue(RECAST).onChanged(t -> {
			showSnap();
			switchType();
		}).build();
	private final DoubleSetting preserve = sgGeneral.doubleSetting("Preserve")
		.description("Put elytras with less durability than this (%) aside, and fly with another.").defaultValue(5).range(0, 50).decimals(0).build();
	/** Snap's value for each type, saved but not shown: {@link #snap} shows and edits the chosen type's. */
	private final EnumSetting<Snap> recastSnap = snapFor(RECAST, Snap.DEGREES_45), altitudeSnap = snapFor(ALTITUDE, Snap.OFF),
		controlSnap = snapFor(CONTROL, Snap.OFF);
	/** The chosen type's Snap; not saved itself (the type's own setting is). */
	private final EnumSetting<Snap> snap = sgGeneral.add(new EnumSetting<>("Snap",
		"Round the way you fly to the nearest multiple of this: 45° for highways and their diagonals, 90° for the axes, Off for "
			+ "exactly the way you faced. Recast: its lane, fixed when you turn it on. Altitude and Control: the heading "
			+ "Lock Rotation holds. Kept for each type.", Snap.DEGREES_45, () -> true) {
		@Override
		public boolean isSerializable() {
			return false;
		}

		/** Edits go to the chosen type's own setting: the player's value, so a control panel's override isn't kept. */
		@Override
		public void changed() {
			super.changed();
			chosenSnap().set(playerValue());
		}

		@Override
		public void reset() {
			set(chosenSnap().defaultValue());
		}

		@Override
		public boolean isDefault() {
			return playerValue() == chosenSnap().defaultValue();
		}
	});

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

	/**
	 * 2: No Durability became the Elytra Swap module and Lock Heading the Lock Rotation module, so they can go in
	 * control panels with any flight (those modules load first, and take over the settings here); Control's Auto Walk
	 * went, the Auto Walk module doing the same.
	 */
	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			// Only a change from the default is saved: No Durability was on unless saved off.
			boolean noDurability = saved.get("General", "No Durability").map(JsonElement::getAsBoolean).orElse(true);
			ElytraSwap.adoptFormerSetting(noDurability, null);
			if (saved.get("General", "Lock Heading").map(JsonElement::getAsBoolean).orElse(false)) {
				LockRotation lock = Modules.get(LockRotation.class);
				if (lock != null) lock.setEnabledSilently(true);
			}
			saved.remove("General", "No Durability");
			saved.remove("General", "Lock Heading");
			// Control's Auto Walk: the Auto Walk module flies it forward now.
			saved.remove("General", "Auto Walk");
		}
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

	double preserve() {
		return preserve.get();
	}

	private EnumSetting<Snap> snapFor(String type, Snap defaultValue) {
		return sgGeneral.enumSetting(type + " Snap", defaultValue).description("Snap for " + type + ".").visible(() -> false)
			.onChanged(v -> {
				if (this.type.get().equals(type)) showSnap();
			}).build();
	}

	/** The chosen type's own Snap setting. */
	private EnumSetting<Snap> chosenSnap() {
		return switch (type.get()) {
			case ALTITUDE -> altitudeSnap;
			case CONTROL -> controlSnap;
			default -> recastSnap;
		};
	}

	/** Shows the chosen type's value in Snap (on a type change, and when its own setting loads). */
	private void showSnap() {
		if (snap != null) snap.set(chosenSnap().playerValue());
	}

	/** {@code yaw} rounded as Snap says; it stays near {@code yaw} (not wrapped), so turning to it stays continuous. */
	float snap(float yaw) {
		int step = snap.get().degrees;
		return step == 0 ? yaw : Math.round(yaw / step) * (float) step;
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
