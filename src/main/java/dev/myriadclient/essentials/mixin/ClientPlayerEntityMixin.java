package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.essentials.modules.movement.ElytraFlight;
import dev.myriadclient.essentials.modules.movement.NoSlow;
import dev.myriadclient.essentials.modules.movement.Velocity;
import dev.myriadclient.essentials.modules.player.WallInteract;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {
	/** Velocity No Push: blocks don't push you out of them. */
	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
	private void essentials$noBlockPush(double x, double z, CallbackInfo ci) {
		if (Velocity.cancelsBlockPush()) ci.cancel();
	}

	/**
	 * Recast: while it has you on foot (Baritone walking you round something, mining, filling a hole), a jump held
	 * into the air doesn't open the elytra, as vanilla would.
	 */
	@WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;tryToStartFallFlying()Z"))
	private boolean essentials$noGlideOnFoot(LocalPlayer self, Operation<Boolean> original) {
		return !ElytraFlight.onFoot() && original.call(self);
	}

	/** Wall Interact: the crosshair target can be an entity or container behind a block. */
	@ModifyReturnValue(method = "raycastHitResult", at = @At("RETURN"))
	private HitResult essentials$wallInteract(HitResult original, float partialTicks, Entity camera) {
		return WallInteract.retarget(original, camera, partialTicks);
	}

	/** No Slow: the item you use doesn't slow you, on the ticks it allows. */
	@ModifyExpressionValue(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;itemUseSpeedMultiplier()F"))
	private float essentials$noSlowItems(float slowed) {
		return NoSlow.itemSpeed(slowed);
	}
}
