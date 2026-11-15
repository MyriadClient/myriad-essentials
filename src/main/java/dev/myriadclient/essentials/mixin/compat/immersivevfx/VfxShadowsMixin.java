package dev.myriadclient.essentials.mixin.compat.immersivevfx;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriadclient.essentials.modules.render.FullBrightImmersiveVfx;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Full Bright under ImmersiveVFX: the brightness its lighting gets, as {@link FullBrightImmersiveVfx#FLAG} while Full Bright is on. */
@Pseudo
@Mixin(targets = "com.shinyflvres.immersivevfx.client.VfxShadows", remap = false)
abstract class VfxShadowsMixin {
	@ModifyExpressionValue(method = "*", at = @At(value = "FIELD", target = "Lcom/shinyflvres/immersivevfx/client/VfxShadows;sunBoost:F",
		opcode = org.objectweb.asm.Opcodes.GETFIELD), require = 0, remap = false)
	private float essentials$fullBright(float brightness) {
		return FullBrightImmersiveVfx.active() ? FullBrightImmersiveVfx.FLAG : brightness;
	}
}
