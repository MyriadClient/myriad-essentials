package dev.myriadclient.essentials.mixin;

import dev.myriadclient.essentials.modules.player.InventoryTweaks;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Inventory Tweaks: drags and the wheel over slots. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin implements InventoryTweaks.ScreenAccess {
	@Shadow
	@Final
	protected AbstractContainerMenu menu;
	@Shadow
	protected boolean isQuickCrafting;
	@Shadow
	@Final
	protected Set<Slot> quickCraftSlots;
	@Shadow
	private boolean skipNextRelease;

	@Shadow
	protected abstract void slotClicked(Slot slot, int slotId, int button, ContainerInput input);

	@Shadow
	private @Nullable Slot getHoveredSlot(double x, double y) {
		throw new AssertionError();
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void essentials$press(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		InventoryTweaks.press(this, getHoveredSlot(event.x(), event.y()), event.button(), event.hasShiftDown());
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void essentials$drag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
		if (InventoryTweaks.drag(this, getHoveredSlot(event.x(), event.y()), event.hasShiftDown())) cir.setReturnValue(true);
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"))
	private void essentials$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
		InventoryTweaks.released(this);
	}

	@Inject(method = "mouseScrolled", at = @At("RETURN"), cancellable = true)
	private void essentials$scroll(double x, double y, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && InventoryTweaks.scroll(this, getHoveredSlot(x, y), scrollY)) cir.setReturnValue(true);
	}

	@Override
	public AbstractContainerMenu essentials$menu() {
		return menu;
	}

	@Override
	public void essentials$click(Slot slot, int button, ContainerInput input) {
		slotClicked(slot, slot.index, button, input);
	}

	@Override
	public void essentials$endVanillaDrag() {
		isQuickCrafting = false;
		quickCraftSlots.clear();
		skipNextRelease = true;
	}
}
