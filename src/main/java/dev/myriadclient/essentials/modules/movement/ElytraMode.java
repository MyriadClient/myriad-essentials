package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.elytra.NoDurability;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.MyriadId;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * One of {@link ElytraFly}'s types: {@link Recast}, which flies its bounce itself ({@link GlideMode}), and
 * {@link Altitude} and {@link ElytraControl}, settings over a core flight ({@link FlightMode}). The type flying hears
 * events only while it flies ({@link #start}, {@link #stop}); its settings are Elytra Fly's, shown only while it's the
 * one chosen. {@link ElytraSwap} decides whether its flight keeps the elytra from wearing, and Preserve is Elytra Fly's.
 */
public abstract class ElytraMode implements NoDurability.Owner, Identified {
	protected static final Minecraft mc = Minecraft.getInstance();
	protected final ElytraFly fly;
	private final String name;
	/** Elytra Fly's General group: the type's settings go there, shown while it's chosen (see {@link #chosen}). */
	protected final SettingGroup sgGeneral;

	protected ElytraMode(ElytraFly fly, String name) {
		this.fly = fly;
		this.name = name;
		this.sgGeneral = fly.settings.group("General");
	}

	/** The type's name, as Elytra Fly's Type shows it. */
	public String name() {
		return name;
	}

	/**
	 * Its id, in Essentials' namespace ({@code myriad-essentials:recast}): what hooks it calls match on
	 * ({@code Match.caller("myriad-essentials:recast")}), as they would a module's.
	 */
	@Override
	public MyriadId id() {
		return MyriadId.of(fly.id().namespace(), MyriadId.toPath(name));
	}

	/** Whether this is the type chosen in Elytra Fly (its settings show then). */
	protected boolean chosen() {
		return fly.typeName().equals(name);
	}

	/** Starts flying: hears events from now on. */
	final void start() {
		onEnable();
		Myriad.events().subscribe(this);
	}

	/** Stops flying. */
	final void stop() {
		Myriad.events().unsubscribe(this);
		onDisable();
	}

	/** The yaw to fly: {@link LockRotation}'s heading while it holds one (rounded as Elytra Fly's Snap says), else {@code camera}. */
	protected float heading(float camera) {
		float yaw = LockRotation.headingFor(this, camera);
		return LockRotation.holding() ? fly.snap(yaw) : yaw;
	}

	/** What Elytra Fly shows beside its name while this flies. */
	public String hudInfo() {
		return null;
	}

	/** Whether the chest slot is this flight's to swap right now: Auto Armor leaves it alone meanwhile. */
	abstract boolean holdsChest();

	protected static boolean inGame() {
		return mc.level != null && mc.player != null;
	}

	@Override
	public void info(String message) {
		fly.info(message);
	}

	@Override
	public void warn(String message) {
		fly.warn(message);
	}

	/** Turns Elytra Fly off. */
	protected void disable() {
		fly.disable();
	}

	protected void onEnable() {
		// Where the server simulates the glide, an elytra worn through a flight goes fast: say how to keep it.
		if (AntiCheat.inEffect().simulatesGlide() && !noDurability()) info("Elytra Swap is off: your elytra wears as you fly.");
	}

	protected void onDisable() {
	}

	/** Elytra Swap's behaviour for this flight (see {@link ElytraSwap#BEHAVIOUR}). */
	private ElytraSwap.Behaviour swap() {
		return ElytraSwap.behaviourFor(this, waitsForElytra());
	}

	/** Whether each glide start should wait for the server to have the elytra back on (Altitude's long, fast glides). */
	protected boolean waitsForElytra() {
		return false;
	}

	@Override
	public boolean noDurability() {
		return swap().swaps();
	}

	@Override
	public boolean equipBeforeStart() {
		return swap().equipBeforeStart();
	}

	/** Disconnects (turning Elytra Fly off first, so it doesn't carry on when you join again). Not in singleplayer. */
	void leave(String why) {
		disable();
		if (mc.isLocalServer() || mc.getConnection() == null) {
			warn(why + ": turning off");
			return;
		}
		mc.getConnection().getConnection().disconnect(Component.literal("[" + name() + "] " + why));
	}
}
