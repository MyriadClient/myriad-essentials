package dev.myriadclient.essentials.mixin;

import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.player.InventoryTweaks;
import dev.myriadclient.essentials.modules.render.Tooltips;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Inventory Tweaks: drags, the wheel and middle-click sorting over slots, and the Box Grid; Tooltips' icon on a carried shulker box. */
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
	protected int leftPos;
	@Shadow
	protected int topPos;
	@Shadow
	@Final
	protected int imageWidth;
	@Shadow
	@Final
	protected int imageHeight;

	@Shadow
	protected abstract void slotClicked(Slot slot, int slotId, int button, ContainerInput input);

	@Shadow
	private @Nullable Slot getHoveredSlot(double x, double y) {
		throw new AssertionError();
	}

	/** Inventory Tweaks' Box Grid, over the window's slots and under the carried item and tooltips. */
	@Inject(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractCarriedItem(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"))
	private void essentials$grid(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		InventoryTweaks.drawGrid(this, graphics, mouseX, mouseY);
	}

	/** Tooltips' icon on a shulker box carried on the cursor, as on one in a slot. */
	@Inject(method = "extractFloatingItem", at = @At("TAIL"))
	private void essentials$carriedIcon(GuiGraphicsExtractor graphics, ItemStack carried, int x, int y, @Nullable String itemCount, CallbackInfo ci) {
		Tooltips tooltips = Modules.active(Tooltips.class);
		if (tooltips != null && tooltips.slotIcons() && Tooltips.isShulker(carried)) Tooltips.drawIcon(graphics, carried, x, y);
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void essentials$press(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (InventoryTweaks.gridPress(this, event.x(), event.y())) {
			cir.setReturnValue(true);
			return;
		}
		Slot slot = getHoveredSlot(event.x(), event.y());
		if (InventoryTweaks.middleClick(this, slot, event.button())) {
			cir.setReturnValue(true);
			return;
		}
		InventoryTweaks.press(this, slot, event.button(), event.hasShiftDown(), event.modifiers());
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void essentials$drag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
		if (InventoryTweaks.drag(this, getHoveredSlot(event.x(), event.y()), event.hasShiftDown())) cir.setReturnValue(true);
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"))
	private void essentials$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
		InventoryTweaks.released(this);
	}

	/** The Box Grid's page scrolls under the wheel, off the window. */
	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
	private void essentials$gridScroll(double x, double y, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
		if (InventoryTweaks.gridScroll(this, x, y, scrollY)) cir.setReturnValue(true);
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
	public int[] essentials$bounds() {
		return new int[]{leftPos, topPos, imageWidth, imageHeight};
	}

	@Override
	public void essentials$endVanillaDrag() {
		isQuickCrafting = false;
		quickCraftSlots.clear();
		skipNextRelease = true;
	}
}
