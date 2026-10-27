package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.essentials.modules.render.Chat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Chat's line backgrounds, open and closed, in the theme's window colour, Minecraft's black or none (Chat → Background). */
@Mixin(targets = {"net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess",
	"net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess"})
public abstract class ChatBackgroundMixin {
	@WrapOperation(method = "fill", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void essentials$background(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		int c = Chat.background(color);
		if (c != 0) original.call(graphics, x0, y0, x1, y1, c);
	}
}
