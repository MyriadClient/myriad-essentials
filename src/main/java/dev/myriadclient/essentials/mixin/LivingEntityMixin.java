package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.essentials.modules.render.ViewModel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** View Model: your swing speed. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
	private int essentials$swingSpeed(int ticks) {
		return (Object) this == Minecraft.getInstance().player ? ViewModel.swingDuration(ticks) : ticks;
	}
}
