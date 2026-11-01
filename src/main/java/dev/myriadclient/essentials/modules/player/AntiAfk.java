package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

/**
 * Keeps the server from kicking you for idling: once you've done nothing for a while, swings your hand and taps sneak,
 * without moving you or your view. Walking, flying, mining or clicking starts the wait again, so it only acts when
 * you're really away. The swing always goes; the sneak waits where crouching would do harm.
 */
public class AntiAfk extends Module {
	private static final int SNEAK_TICKS = 10;
	/** Blocks in a tick, squared: a tick moving faster along the ground is you going somewhere, not bobbing in water. */
	private static final double MOVING_SQR = 0.1 * 0.1;

	private final BoolSetting swing = sgGeneral.bool("Swing").description("Swing your hand.").defaultValue(true).build();
	private final BoolSetting sneak = sgGeneral.bool("Sneak").description("Tap sneak, where it's safe to.").defaultValue(true).build();
	private final IntSetting delay = sgGeneral.integer("Delay").description("Seconds of doing nothing between actions.")
		.defaultValue(60).range(10, 300).build();

	/** When you last did something ({@code System.currentTimeMillis}): the game ticks slowly in the background, the server's timer doesn't. */
	private long activeAt;
	private int sneakLeft;
	private double lastX, lastZ;

	public AntiAfk() {
		super(Tags.AUTOMATION, "Anti AFK", "Swing and crouch while you're away, so the server doesn't kick you for idling.");
	}

	@Override
	protected void onEnable() {
		restart();
	}

	@Override
	protected void onDisable() {
		restart();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		restart();
	}

	/** Anything you do that the server hears about, this module's own swing included. */
	@Subscribe(packets = {ServerboundSwingPacket.class, ServerboundPlayerActionPacket.class, ServerboundUseItemOnPacket.class,
		ServerboundUseItemPacket.class, ServerboundInteractPacket.class, ServerboundContainerClickPacket.class})
	private void onSend(PacketEvent.Send e) {
		activeAt = System.currentTimeMillis();
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		LocalPlayer player = mc.player;
		double dx = player.getX() - lastX, dz = player.getZ() - lastZ;
		lastX = player.getX();
		lastZ = player.getZ();
		long now = System.currentTimeMillis();
		if (dx * dx + dz * dz > MOVING_SQR || !player.isAlive()) {
			activeAt = now;
			return;
		}
		if (now - activeAt < delay.get() * 1000L) return;
		activeAt = now;
		if (swing.get()) player.swing(InteractionHand.MAIN_HAND);
		if (sneak.get() && !player.isShiftKeyDown() && sneakSafe(player)) sneakLeft = SNEAK_TICKS;
	}

	@Subscribe(inGame = true)
	private void onInput(InputEvent e) {
		if (sneakLeft <= 0) return;
		sneakLeft--;
		if (sneakSafe(mc.player)) e.sneak = true;
		else sneakLeft = 0;
	}

	/** Crouching dismounts you, sinks you in water, stops you on a ladder, and gets in the way of what you're using. */
	private static boolean sneakSafe(LocalPlayer player) {
		return player.onGround() && !player.isPassenger() && !player.isInWater() && !player.isInLava() && !player.isFallFlying()
			&& !player.onClimbable() && !player.isUsingItem();
	}

	private void restart() {
		activeAt = System.currentTimeMillis();
		sneakLeft = 0;
	}
}
