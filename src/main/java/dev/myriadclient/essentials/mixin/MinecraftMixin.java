package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.movement.TickShift;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tick Shift's burst: extra ticks in the frame's tick loop, whose count is read again before every tick, so ticks
 * saved can run straight after the one that moves you off, in the same frame.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void essentials$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		TickShift shift = Modules.active(TickShift.class);
		if (shift != null) shift.frameStart();
	}

	@ModifyExpressionValue(method = "runTick", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
	private int essentials$burst(int ticks) {
		TickShift shift = Modules.active(TickShift.class);
		return shift == null ? ticks : ticks + shift.extraTicks();
	}
}
