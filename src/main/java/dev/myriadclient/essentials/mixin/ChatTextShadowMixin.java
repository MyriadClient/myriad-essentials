package dev.myriadclient.essentials.mixin;

import dev.myriadclient.essentials.modules.render.Chat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Chat's text without its shadow when Chat → Text Shadow is off (Minecraft always gives it one). */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
public abstract class ChatTextShadowMixin {
	@ModifyArg(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiTextRenderState;<init>(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;Lorg/joml/Matrix3x2fc;IIIIZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V"),
		index = 7)
	private boolean essentials$shadow(boolean shadow) {
		return shadow && (!Chat.drawing || Chat.shadowed());
	}
}
