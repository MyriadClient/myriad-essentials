package dev.myriadclient.essentials.mixin;

import dev.myriadclient.essentials.modules.render.NoRender;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void essentials$fire(CallbackInfo ci) {
		if (NoRender.hides(n -> n.fire)) ci.cancel();
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void essentials$inWall(CallbackInfo ci) {
		if (NoRender.hides(n -> n.blockOverlay)) ci.cancel();
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void essentials$underwater(CallbackInfo ci) {
		if (NoRender.hides(n -> n.liquidOverlay)) ci.cancel();
	}
}
