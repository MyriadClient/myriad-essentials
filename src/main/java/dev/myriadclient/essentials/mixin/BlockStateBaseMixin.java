package dev.myriadclient.essentials.mixin;

import dev.myriadclient.essentials.modules.render.NoRender;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	/** No Render: no random model offsets (they reveal block positions). */
	@Inject(method = "getOffset", at = @At("HEAD"), cancellable = true)
	private void essentials$noOffset(BlockPos pos, CallbackInfoReturnable<Vec3> cir) {
		if (NoRender.hides(n -> n.textureRotations)) cir.setReturnValue(Vec3.ZERO);
	}

	/**
	 * No Render: every block picks the same of its model's random variants (which way stone or dirt is turned reveals
	 * where it is).
	 */
	@Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
	private void essentials$noRotation(BlockPos pos, CallbackInfoReturnable<Long> cir) {
		if (NoRender.hides(n -> n.textureRotations)) cir.setReturnValue(0L);
	}
}
