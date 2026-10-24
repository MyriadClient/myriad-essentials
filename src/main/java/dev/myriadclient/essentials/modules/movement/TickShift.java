package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.IntSetting;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Saves ticks while you stand still and spends them all at once as you move off: a burst of up to half a second of
 * movement in one frame. Grim allows it: its Timer gives each tick packet 50 ms against real time and only moves its
 * view of your clock on a ping answered after a tick packet, so ticks that send nothing build up time to spend. Saving
 * means sending nothing: no idle position reminder, and (on 1.21.2 and newer) no tick end while nothing moved, which is
 * all Grim's TickTimer looks for. The burst runs inside the frame that moves you off, before the client answers the
 * next ping: the frame's ticks go one after another with no packets handled between them, and Grim then counts them as
 * time you'd saved. A frame runs ten ticks at most, so that's as many as it saves.
 */
public class TickShift extends Module {
	private final IntSetting ticks = sgGeneral.integer("Ticks").description("Ticks saved while you stand still, spent in one burst as you move off.")
		.defaultValue(10).range(1, 10).build();

	/** Ticks saved so far. */
	private int charge;
	/** Saved ticks to run in this frame, and how many it's been given. */
	private int pending, granted;
	/** Whether a tick packet went out this tick (then the saving starts over). */
	private boolean sent;
	/** What the last movement packet said, to tell an idle reminder from a move. */
	private double lastX = Double.NaN, lastY, lastZ;
	private float lastYaw, lastPitch;
	private boolean lastOnGround;

	public TickShift() {
		super(Tags.MOVEMENT, "Tick Shift", "Save ticks while you stand still and spend them in one burst as you move off.");
	}

	@Override
	protected void onDisable() {
		charge = pending = 0;
		lastX = Double.NaN;
	}

	@Override
	public String hudInfo() {
		return String.valueOf(charge);
	}

	@Subscribe(inGame = true)
	private void onTickStart(TickEvent.Pre e) {
		sent = false;
	}

	/** Moving off with ticks saved: spend them all in this frame, straight after this tick. */
	@Subscribe(inGame = true, priority = Priority.LOWEST)
	private void onInput(InputEvent e) {
		if (charge > 0 && e.isMoving()) {
			pending = charge;
			charge = 0;
		}
	}

	@Subscribe(inGame = true, packets = {ServerboundMovePlayerPacket.class, ServerboundClientTickEndPacket.class})
	private void onSend(PacketEvent.Send e) {
		if (e.packet() instanceof ServerboundMovePlayerPacket p) {
			if (saving() && idle(p)) {
				e.cancel();
				return;
			}
			remember(p);
			sent = true;
		} else if (!sent && saving()) {
			// Nothing moved this tick: a tick end alone would count as a tick.
			e.cancel();
		}
	}

	@Subscribe(inGame = true)
	private void onTickEnd(TickEvent.Post e) {
		charge = sent ? 0 : Math.min(ticks.get(), charge + 1);
	}

	/** Standing still with nothing to spend now. */
	private boolean saving() {
		return pending == 0 && granted == 0;
	}

	/** A packet saying nothing new: vanilla's reminder of where you stand. */
	private boolean idle(ServerboundMovePlayerPacket p) {
		if (Double.isNaN(lastX) || p.isOnGround() != lastOnGround) return false;
		return p.getX(lastX) == lastX && p.getY(lastY) == lastY && p.getZ(lastZ) == lastZ
			&& p.getYRot(lastYaw) == lastYaw && p.getXRot(lastPitch) == lastPitch;
	}

	private void remember(ServerboundMovePlayerPacket p) {
		lastX = p.getX(lastX);
		lastY = p.getY(lastY);
		lastZ = p.getZ(lastZ);
		lastYaw = p.getYRot(lastYaw);
		lastPitch = p.getXRot(lastPitch);
		lastOnGround = p.isOnGround();
	}

	/** A new frame: nothing given to it yet. */
	public void frameStart() {
		granted = 0;
	}

	/** Extra ticks for the frame running now: what it's been given, with anything saved and just set to spend. */
	public int extraTicks() {
		if (pending > 0) {
			granted += pending;
			pending = 0;
		}
		return granted;
	}
}
