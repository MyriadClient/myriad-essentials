package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.essentials.modules.movement.ElytraFlight;
import dev.myriadclient.essentials.modules.movement.NoSlow;
import dev.myriadclient.essentials.modules.movement.Velocity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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

	/** No Slow Webs: a web or berry bush doesn't hold the local player back (it still breaks a fall, as vanilla's does). */
	@Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
	private void essentials$noSlowWebs(BlockState state, Vec3 speedMultiplier, CallbackInfo ci) {
		Object self = this;
		if (self == Minecraft.getInstance().player && NoSlow.ignores(state)) {
			((Entity) self).resetFallDistance();
			ci.cancel();
		}
	}
}
