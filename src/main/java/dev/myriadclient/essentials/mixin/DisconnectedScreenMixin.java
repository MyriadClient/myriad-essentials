package dev.myriadclient.essentials.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.player.AutoReconnect;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Reconnect's button on the disconnect screen: when it joins again, or a click to join at once. */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin {
	@Shadow
	@Final
	private LinearLayout layout;

	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V"))
	private void essentials$reconnectButton(CallbackInfo ci) {
		AutoReconnect reconnect = Modules.get(AutoReconnect.class);
		if (reconnect == null || Myriad.server().lastAddress() == null) return;
		layout.addChild(new Button.Plain(0, 0, 200, 20, Component.literal(reconnect.buttonText()), b -> reconnect.joinNow(), narration -> narration.get()) {
			// The countdown, as it is when drawn.
			@Override
			public Component getMessage() {
				return Component.literal(reconnect.buttonText());
			}
		});
	}
}
