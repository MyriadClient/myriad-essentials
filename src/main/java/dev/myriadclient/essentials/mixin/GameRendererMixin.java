package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.render.FullBright;
import dev.myriadclient.essentials.modules.render.NoRender;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void essentials$hurtCam(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (NoRender.hides(n -> n.hurtCam)) ci.cancel();
	}

	/**
	 * Full Bright under a shader pack: Iris hands packs this as their {@code nightVision} uniform (which they brighten
	 * dark places by) without checking for the effect, while vanilla only asks with night vision on. So shader packs see
	 * full night vision and vanilla's look is unchanged.
	 */
	@Inject(method = "nightVisionScale", at = @At("HEAD"), cancellable = true)
	private static void essentials$fullBright(LivingEntity entity, float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (Modules.active(FullBright.class) != null) cir.setReturnValue(1f);
	}

	/** No Render Nausea: the screen warp reads nausea's strength here; report none. */
	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;nauseaEffectIntensity:F", opcode = Opcodes.GETFIELD))
	private float essentials$nausea(float intensity) {
		return NoRender.hides(n -> n.nausea) ? 0 : intensity;
	}

	/**
	 * No Render Portal Overlay: walking through a portal warps the screen the way nausea does, from the portal's own
	 * strength (the HUD only draws the purple overlay); report none.
	 */
	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;portalEffectIntensity:F", opcode = Opcodes.GETFIELD))
	private float essentials$portalWarp(float intensity) {
		return NoRender.hides(n -> n.portal) ? 0 : intensity;
	}
}
