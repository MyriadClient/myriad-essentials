package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.essentials.modules.render.ExtraTab;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player list as Extra Tab has it: how many, how many rows, ping as a number, names picked out, header and footer. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	/** The widest ping text a slot is made room for (four digits and "ms"). */
	private static final String WIDEST_PING = "9999ms";

	/**
	 * The limit as it reaches the stream, not Minecraft's 80 itself: other mods (Meteor's Better Tab) change that
	 * constant too, and only one ModifyConstant can. The constants below are changed with ModifyExpressionValue, which
	 * chains with other mods' changes.
	 */
	@ModifyArg(method = "getPlayerInfos", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;limit(J)Ljava/util/stream/Stream;"))
	private long essentials$players(long vanilla) {
		ExtraTab m = ExtraTab.on();
		return m != null ? m.players.get() : vanilla;
	}

	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "CONSTANT", args = "intValue=20"))
	private int essentials$rows(int vanilla) {
		ExtraTab m = ExtraTab.on();
		return m != null ? m.rows.get() : vanilla;
	}

	/** Room in each slot for the ping as text, where Minecraft leaves 13 for the bars' 10 and a gap. */
	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "CONSTANT", args = "intValue=13"))
	private int essentials$pingRoom(int vanilla) {
		ExtraTab m = ExtraTab.on();
		if (m == null || m.ping.get() != ExtraTab.Ping.NUMBER) return vanilla;
		return vanilla - 10 + Minecraft.getInstance().font.width(WIDEST_PING) + 1;
	}

	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;header:Lnet/minecraft/network/chat/Component;"))
	private @Nullable Component essentials$header(@Nullable Component header) {
		ExtraTab m = ExtraTab.on();
		return m != null && !m.headerFooter.get() ? null : header;
	}

	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;footer:Lnet/minecraft/network/chat/Component;"))
	private @Nullable Component essentials$footer(@Nullable Component footer) {
		ExtraTab m = ExtraTab.on();
		return m != null && !m.headerFooter.get() ? null : footer;
	}

	@ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
	private Component essentials$name(Component name, PlayerInfo info) {
		ExtraTab m = ExtraTab.on();
		return m != null ? m.name(info, name) : name;
	}

	/** The ping in milliseconds, right-aligned where the bars go, coloured as they step. */
	@Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
	private void essentials$ping(GuiGraphicsExtractor graphics, int slotWidth, int xo, int yo, PlayerInfo info, CallbackInfo ci) {
		ExtraTab m = ExtraTab.on();
		if (m == null || m.ping.get() != ExtraTab.Ping.NUMBER) return;
		var font = Minecraft.getInstance().font;
		String text = ExtraTab.pingText(info.getLatency());
		graphics.text(font, text, xo + slotWidth - font.width(text) - 1, yo, ExtraTab.pingColor(info.getLatency()));
		ci.cancel();
	}
}
