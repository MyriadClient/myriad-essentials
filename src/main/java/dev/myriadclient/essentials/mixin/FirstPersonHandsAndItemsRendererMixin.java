package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.render.ViewModel;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	private static final String ROTATE = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V";

	/** The first two rotations in submitHandsWithItems are the camera sway (pitch, then yaw). */
	@WrapWithCondition(method = "submitHandsWithItems", at = {@At(value = "INVOKE", target = ROTATE, ordinal = 0), @At(value = "INVOKE", target = ROTATE, ordinal = 1)})
	private boolean essentials$sway(PoseStack poseStack, Axis axis, float degrees) {
		ViewModel vm = Modules.active(ViewModel.class);
		return vm == null || !vm.noSway.get();
	}

	@WrapOperation(method = "submitHandsWithItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
	private void essentials$viewModel(FirstPersonHandsAndItemsRenderer renderer, PlayerRenderState player, FirstPersonHandsAndItemsRenderState state, float tickDelta, float pitch,
									  InteractionHand hand, float swing, ItemStack item, float equip, PoseStack matrices, SubmitNodeCollector consumers, int light, Operation<Void> original) {
		ViewModel vm = Modules.active(ViewModel.class);
		if (vm == null) {
			original.call(renderer, player, state, tickDelta, pitch, hand, swing, item, equip, matrices, consumers, light);
			return;
		}
		boolean main = hand == InteractionHand.MAIN_HAND;
		if (main ? vm.hideMain.get() : vm.hideOff.get()) return;
		HumanoidArm arm = hand.asArm(player.avatarRenderState.mainArm);
		float mirror = arm == HumanoidArm.RIGHT ? 1 : -1;
		matrices.pushPose();
		boolean own = !main && vm.separateOffhand.get();
		matrices.translate((own ? vm.offX.get() : vm.x.get()) * mirror, own ? vm.offY.get() : vm.y.get(), own ? vm.offZ.get() : vm.z.get());
		matrices.rotateDegrees(Axis.YP, vm.rotY.get() * mirror);
		matrices.rotateDegrees(Axis.XP, vm.rotX.get());
		matrices.rotateDegrees(Axis.ZP, vm.rotZ.get() * mirror);
		float s = main ? vm.scale.getFloat() : vm.offhandScale.getFloat();
		if (s != 1) {
			// Scale around where the item sits rather than the camera, so it grows in place.
			float px = 0.56f * mirror, py = -0.52f, pz = -0.72f;
			matrices.translate(px, py, pz);
			matrices.scale(s, s, s);
			matrices.translate(-px, -py, -pz);
		}
		int l = vm.brighten.get() ? LightCoordsUtil.FULL_BRIGHT : light;
		original.call(renderer, player, state, tickDelta, pitch, hand, swing, item, equip, matrices, consumers, l);
		matrices.popPose();
	}
}
