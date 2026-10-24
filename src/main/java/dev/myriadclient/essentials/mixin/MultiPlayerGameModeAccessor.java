package dev.myriadclient.essentials.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How far your own hand has got breaking a block, for the Looking At HUD's break bar. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Accessor("destroyBlockPos")
	BlockPos essentials$destroyBlockPos();

	@Accessor("destroyProgress")
	float essentials$destroyProgress();
}
