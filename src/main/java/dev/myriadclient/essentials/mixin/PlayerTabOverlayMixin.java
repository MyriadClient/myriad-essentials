package dev.myriadclient.essentials.mixin;

import dev.myriadclient.essentials.modules.render.ExtraTab;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player list as Extra Tab draws it, in place of Minecraft's, from a layer after the HUD so it's on top. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	@Shadow
	private @Nullable Component header;
	@Shadow
	private @Nullable Component footer;

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void essentials$draw(GuiGraphicsExtractor graphics, int screenWidth, Scoreboard scoreboard, @Nullable Objective objective, CallbackInfo ci) {
		ExtraTab m = ExtraTab.on();
		if (m == null) return;
		m.show(screenWidth, scoreboard, objective, header, footer);
		ci.cancel();
	}
}
