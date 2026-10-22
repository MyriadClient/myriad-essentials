package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.myriadclient.essentials.modules.render.NoRender;
import dev.myriadclient.essentials.modules.render.ViewModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** View Model: your swing speed. No Render: your own effect particles. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
	private int essentials$swingSpeed(int ticks) {
		return (Object) this == Minecraft.getInstance().player ? ViewModel.swingDuration(ticks) : ticks;
	}

	@WrapWithCondition(method = "tickEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private boolean essentials$ownEffectParticles(Level level, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
		return (Object) this != Minecraft.getInstance().player || !NoRender.hides(n -> n.ownEffectParticles);
	}
}
