package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriadclient.api.render.ThemeFont;
import dev.myriadclient.essentials.modules.render.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

/** Chat as the Chat module has it: its font and rows, filters, repeats stacked, history, hidden while closed. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	/** The text of the last message, the message it was added as, and how many times it came in a row. */
	@Unique
	private @Nullable String essentials$repeatOf;
	@Unique
	private @Nullable GuiMessage essentials$repeated;
	@Unique
	private int essentials$repeats;

	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	private void refreshTrimmedMessages() {
	}

	/** Chat's lines split, and so drawn, in the theme's font while it's chat's; the message itself is kept as it came. */
	@WrapOperation(method = "addMessageToDisplayQueue", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
	private List<FormattedCharSequence> essentials$themeFont(GuiMessage message, Font font, int width, Operation<List<FormattedCharSequence>> original) {
		if (!Chat.themeFont()) return original.call(message, font, width);
		return original.call(new GuiMessage(message.addedTime(), ThemeFont.styled(message.content()), message.signature(), message.source(), message.tag()), font, width);
	}

	/**
	 * As many messages kept as History says, where Minecraft keeps 100. Constants are changed with
	 * ModifyExpressionValue throughout, which other mods' changes to the same value (another chat mod's history)
	 * chain with: a ModifyConstant fails, and fails the game's start, when another mod has changed it first.
	 */
	@ModifyExpressionValue(method = {"addMessageToQueue", "addMessageToDisplayQueue"}, at = @At(value = "CONSTANT", args = "intValue=100"))
	private int essentials$history(int vanilla) {
		return Chat.kept();
	}

	/** Messages a filter is in aren't shown, as Minecraft's own filter hides others. */
	@ModifyExpressionValue(method = {"addMessage", "refreshTrimmedMessages"}, at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;visibleMessageFilter:Ljava/util/function/Predicate;"))
	private Predicate<GuiMessage> essentials$filters(Predicate<GuiMessage> vanilla) {
		return vanilla.and(Chat::shown);
	}

	/**
	 * The same message again replaces the last, with how many times it came (Stack Repeats): the last one's lines are
	 * taken away and it's added again counted.
	 */
	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
	private Component essentials$stack(Component contents) {
		if (!Chat.stacks()) {
			essentials$repeatOf = null;
			return contents;
		}
		String text = contents.getString();
		if (text.equals(essentials$repeatOf) && !allMessages.isEmpty() && allMessages.getFirst() == essentials$repeated) {
			allMessages.removeFirst();
			refreshTrimmedMessages();
			return contents.copy().append(Component.literal(" (x" + ++essentials$repeats + ")").withStyle(ChatFormatting.GRAY));
		}
		essentials$repeatOf = text;
		essentials$repeats = 1;
		return contents;
	}

	/** The message just added, which a repeat replaces. */
	@Inject(method = "addMessageToQueue", at = @At("TAIL"))
	private void essentials$added(GuiMessage message, CallbackInfo ci) {
		essentials$repeated = message;
	}

	/** Chat drawing: its text shadow and line backgrounds follow the module (ChatTextShadowMixin, ChatBackgroundMixin). */
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
		at = @At("HEAD"), cancellable = true)
	private void essentials$drawing(GuiGraphicsExtractor graphics, Font font, int ticks, int mouseX, int mouseY, ChatComponent.DisplayMode mode, boolean cursor, CallbackInfo ci) {
		// Shown only while open: the HUD's chat (any mod's that draws it) isn't, the open chat screen's (foreground) is.
		if (!mode.foreground && Chat.hiddenClosed()) {
			ci.cancel();
			return;
		}
		Chat.drawing = Chat.on() != null;
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("RETURN"))
	private void essentials$drawn(CallbackInfo ci) {
		Chat.drawing = false;
	}

	/**
	 * Chat's rows in the theme's font: as tall as it is (Chat.rowHeight), not Minecraft's 9, with the text placed as far
	 * up them as vanilla places its own (its 8, the row less the gap under the text), and as many lines a page.
	 */
	@ModifyExpressionValue(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
		at = @At(value = "CONSTANT", args = "intValue=9"))
	private int essentials$rowHeight(int height) {
		return Chat.rowHeight();
	}

	@ModifyExpressionValue(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
		at = @At(value = "CONSTANT", args = "doubleValue=8.0"))
	private double essentials$textInRow(double offset) {
		return Chat.rowHeight() == 9 ? offset : Chat.rowHeight();
	}

	@ModifyExpressionValue(method = "getLineHeight", at = @At(value = "CONSTANT", args = "doubleValue=9.0"))
	private double essentials$lineHeight(double height) {
		return Chat.rowHeight();
	}
}
