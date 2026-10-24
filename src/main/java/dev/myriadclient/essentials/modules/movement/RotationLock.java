package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.EnumSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Keeps flights going one way whatever the camera does: the heading is locked when the glide starts (or when you turn
 * this on, with Lock At: Turned On) to the exact yaw you faced, kept through a glide Elytra Swap restarts in mid-air,
 * and let go when you land. The camera stays yours to look around. Flights that steer by heading follow it
 * ({@link ElytraFly}'s Altitude and Control); put it in a control panel with them for a straight cruise.
 * <p>
 * For addon developers: {@link #headingFor} gives a flight the yaw to fly while a heading is locked, through the
 * {@link #HEADING} hook, so an addon can change it for one flight (snap it to the nearest axis, follow a path):
 *
 * <pre>{@code
 * ctx.hook(RotationLock.HEADING)
 *     .named("Control snaps to axes")
 *     .when(Match.caller("myriad-essentials:control"))
 *     .handle(call -> Math.round(call.input().locked() / 45f) * 45f);
 * }</pre>
 */
public class RotationLock extends Module {
	public enum LockAt {
		/** When each glide starts: take off facing the way to go. */
		TAKEOFF,
		/** When it's turned on (a bind mid-flight). */
		TURNED_ON
	}

	/** A locked heading as a flight asks for it: the yaw locked, and where the camera looks now (degrees). */
	public record Heading(float locked, float camera) {
	}

	/** The yaw a flight flies while a heading is locked (default: the one locked). The caller is the flight. */
	public static final Hook<Heading, Float> HEADING = Hook.of("myriad-essentials:locked_heading", "Locked heading",
		"The yaw a flight flies while Rotation Lock holds its heading.");

	private final EnumSetting<LockAt> lockAt = sgGeneral.oneOf("Lock At", LockAt.TAKEOFF)
		.description("Takeoff: lock the way you face as each glide starts. Turned On: lock the way you face when it's turned on.").build();

	/** The yaw locked, or NaN while nothing is. */
	private float locked = Float.NaN;

	public RotationLock() {
		super(Tags.MOVEMENT, "Rotation Lock", "Keep flying the way you faced, whatever the camera does.");
		formerNames("Lock Rotation");
	}

	/**
	 * The yaw {@code flight} should fly: the locked heading (through {@link #HEADING}) while Rotation Lock is on and
	 * holds one, otherwise {@code camera}.
	 */
	public static float headingFor(Object flight, float camera) {
		RotationLock lock = Modules.active(RotationLock.class);
		if (lock == null || Float.isNaN(lock.locked)) return camera;
		Heading asked = new Heading(lock.locked, camera);
		if (!HEADING.hooked()) return asked.locked();
		Float yaw = HEADING.call(flight, asked, Heading::locked);
		return yaw == null || yaw.isNaN() ? asked.locked() : yaw;
	}

	/** Whether a heading is locked right now. */
	public static boolean holding() {
		RotationLock lock = Modules.active(RotationLock.class);
		return lock != null && !Float.isNaN(lock.locked);
	}

	@Override
	protected void onEnable() {
		locked = lockAt.get() == LockAt.TURNED_ON && inGame() ? mc.player.getYRot() : Float.NaN;
	}

	@Override
	protected void onDisable() {
		locked = Float.NaN;
	}

	@Override
	public String hudInfo() {
		return Float.isNaN(locked) ? null : String.format("%.0f°", Mth.wrapDegrees(locked));
	}

	/** Before the flights tick, so they fly this tick's heading. */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 30, inGame = true)
	private void onTick(TickEvent.Pre e) {
		LocalPlayer p = mc.player;
		// The lock lasts the flight: a glide Elytra Swap restarts in the air keeps it, a landing ends it.
		if (lockAt.get() == LockAt.TAKEOFF) {
			if (p.onGround() || p.isInWater()) locked = Float.NaN;
			else if (p.isFallFlying() && Float.isNaN(locked)) locked = p.getYRot();
		}
	}
}
