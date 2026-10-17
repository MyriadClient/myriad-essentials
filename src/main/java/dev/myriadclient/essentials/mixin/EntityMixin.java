package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.essentials.modules.movement.ElytraFlight;
import dev.myriadclient.essentials.modules.movement.Velocity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
	/** Velocity No Push: other entities don't shove the local player. */
	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void essentials$noEntityPush(Entity entity, CallbackInfo ci) {
		Object self = this;
		if (self == Minecraft.getInstance().player && Velocity.cancelsEntityPush()) ci.cancel();
	}

	/** Recast: stay sprinting while gliding without holding forward, so each bounce's jump gets the sprint boost. */
	@ModifyReturnValue(method = "isSprinting", at = @At("RETURN"))
	private boolean essentials$bounceSprint(boolean original) {
		Object self = this;
		return original || self == Minecraft.getInstance().player && ElytraFlight.holdsSprint();
	}
}
