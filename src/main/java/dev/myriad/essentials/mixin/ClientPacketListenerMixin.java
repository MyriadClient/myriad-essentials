package dev.myriad.essentials.mixin;

import dev.myriad.essentials.modules.movement.ElytraFlight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Elytra Fly: a setback doesn't turn the camera to the flight's rotation. Some servers (6b6t's NoCheatPlus) set you
 * back to the rotation you last sent, absolute, and while Elytra Fly flies with a rotation of its own that's its dive
 * (straight down), not where you were looking. The server still gets the rotation it sent back (vanilla answers the
 * setback with it before this runs).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Unique
	private float essentials$yaw, essentials$pitch;
	@Unique
	private boolean essentials$keepView;

	@Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void essentials$beforeSetBack(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		var p = Minecraft.getInstance().player;
		essentials$keepView = p != null && ElytraFlight.spoofing();
		if (!essentials$keepView) return;
		essentials$yaw = p.getYRot();
		essentials$pitch = p.getXRot();
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void essentials$afterSetBack(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		var p = Minecraft.getInstance().player;
		if (!essentials$keepView || p == null) return;
		essentials$keepView = false;
		p.setYRot(essentials$yaw);
		p.setXRot(essentials$pitch);
		p.yRotO = essentials$yaw;
		p.xRotO = essentials$pitch;
	}
}
