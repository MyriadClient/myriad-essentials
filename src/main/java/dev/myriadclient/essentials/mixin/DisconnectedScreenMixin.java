package dev.myriadclient.essentials.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.util.Format;
import dev.myriadclient.essentials.modules.combat.AutoDisconnect;
import dev.myriadclient.essentials.modules.combat.DisconnectReport;
import dev.myriadclient.essentials.modules.player.AutoReconnect;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * On the disconnect screen: when Auto Disconnect made you leave and how long ago, for coming back to it later, with its
 * report (DisconnectReport: the screen as you left and what happened), and Auto Reconnect's button, when it joins again
 * or a click to join at once.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
	@Shadow
	@Final
	@Mutable
	private LinearLayout layout;

	@Shadow
	@Final
	private Screen parent;

	@Shadow
	@Final
	private Component buttonText;

	@Shadow
	@Final
	private DisconnectionDetails details;

	protected DisconnectedScreenMixin(Component title) {
		super(title);
	}

	/**
	 * When Auto Disconnect made you leave: its report (DisconnectReport) in place of Minecraft's title and reason, and the
	 * buttons side by side under it. A layout of its own each time, as the screen's laid out again on a resize.
	 */
	@Inject(method = "init", at = @At("HEAD"), cancellable = true)
	private void essentials$report(CallbackInfo ci) {
		AutoDisconnect disconnect = Modules.get(AutoDisconnect.class);
		AutoDisconnect.Report report = disconnect == null ? null : disconnect.report();
		if (report == null || report.at() != disconnect.leftAt() || !details.reason().getString().startsWith(AutoDisconnect.PREFIX)) return;
		ci.cancel();
		int w = Math.min(width - 48, 520);
		layout = LinearLayout.vertical().spacing(14);
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(DisconnectReport.of(disconnect, report, w, height - 80, disconnect.pictured()));
		LinearLayout buttons = LinearLayout.horizontal().spacing(8);
		AutoReconnect reconnect = Modules.get(AutoReconnect.class);
		boolean again = reconnect != null && Myriad.server().lastAddress() != null;
		int bw = again ? (w - 8) / 2 : Math.min(w, 200);
		buttons.addChild(minecraft.allowsMultiplayer() ? Button.builder(buttonText, b -> minecraft.gui.setScreen(parent)).width(bw).build()
			: Button.builder(Component.translatable("gui.toTitle"), b -> minecraft.gui.setScreen(new TitleScreen())).width(bw).build());
		if (again) buttons.addChild(reconnectButton(reconnect, bw));
		layout.addChild(buttons);
		layout.arrangeElements();
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	// Without the report (it left before this version, say): just after the reason, before init's second
	// defaultCellSetting(), which narrows the padding for the buttons.
	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;defaultCellSetting()Lnet/minecraft/client/gui/layouts/LayoutSettings;", ordinal = 1))
	private void essentials$leftAt(CallbackInfo ci) {
		AutoDisconnect disconnect = Modules.get(AutoDisconnect.class);
		if (disconnect == null || disconnect.leftAt() == 0 || !details.reason().getString().startsWith(AutoDisconnect.PREFIX)) return;
		long at = disconnect.leftAt();
		String clock = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
		// Counting up as it's drawn.
		layout.addChild(new StringWidget(width - 50, 9, Component.empty(), font) {
			@Override
			public Component getMessage() {
				return Component.literal("Left at " + clock + ", " + Format.duration(System.currentTimeMillis() - at) + " ago").withStyle(ChatFormatting.GRAY);
			}

			@Override
			public void visitLines(ActiveTextCollector output) {
				Component text = getMessage();
				output.accept(getX() + (getWidth() - getFont().width(text)) / 2, getY(), text.getVisualOrderText());
			}
		});
	}

	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V"))
	private void essentials$reconnectButton(CallbackInfo ci) {
		AutoReconnect reconnect = Modules.get(AutoReconnect.class);
		if (reconnect == null || Myriad.server().lastAddress() == null) return;
		layout.addChild(reconnectButton(reconnect, 200));
	}

	private static Button reconnectButton(AutoReconnect reconnect, int width) {
		return new Button.Plain(0, 0, width, 20, Component.literal(reconnect.buttonText()), b -> reconnect.joinNow(), narration -> narration.get()) {
			// The countdown, as it is when drawn.
			@Override
			public Component getMessage() {
				return Component.literal(reconnect.buttonText());
			}
		};
	}
}
