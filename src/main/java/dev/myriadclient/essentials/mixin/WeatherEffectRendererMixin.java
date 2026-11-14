package dev.myriadclient.essentials.mixin;

import dev.myriadclient.essentials.modules.render.NoRender;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
	@Inject(method = {"render", "renderOit"}, at = @At("HEAD"), cancellable = true)
	private void essentials$noWeather(CallbackInfo ci) {
		if (NoRender.hides(n -> n.weather)) ci.cancel();
	}
}
