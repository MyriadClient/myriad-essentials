package dev.myriadclient.essentials.mixin;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.item.Slots;
import dev.myriadclient.essentials.modules.render.ViewModel;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonHandsAndItemsMixin {
	@Shadow
	private ItemStack mainHandItem;
	@Shadow
	private ItemStack offHandItem;
	@Shadow
	private float mainHandHeight;
	@Shadow
	private float oMainHandHeight;
	@Shadow
	private float offHandHeight;
	@Shadow
	private float oOffHandHeight;

	/** View Model No Switch Animation: no dip when switching items; show the new item straight away. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void essentials$noSwitchAnimation(LocalPlayer player, CallbackInfo ci) {
		if (!ViewModel.noSwitchAnimation()) return;
		mainHandItem = Myriad.inventory().shown(player.getInventory().getSelectedSlot());
		offHandItem = Myriad.inventory().shown(Slots.OFF_HAND);
		mainHandHeight = oMainHandHeight = 1;
		offHandHeight = oOffHandHeight = 1;
	}
}
