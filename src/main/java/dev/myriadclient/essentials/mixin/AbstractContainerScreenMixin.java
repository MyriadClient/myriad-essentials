package dev.myriadclient.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.myriadclient.api.item.ItemInfo;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.player.InventoryTweaks;
import dev.myriadclient.essentials.modules.render.Tooltips;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


/**
 * Inventory Tweaks: shift-drags, the wheel and middle-clicks over slots, and the Box Grid; Tooltips' icon on a carried
 * shulker box.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin implements InventoryTweaks.ScreenAccess {
	@Shadow
	@Final
	protected AbstractContainerMenu menu;
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
	protected @Nullable Slot hoveredSlot;

	@Shadow
	protected abstract void slotClicked(@Nullable Slot slot, int slotId, int button, ContainerInput input);

	@Shadow
	private @Nullable Slot getHoveredSlot(double x, double y) {
		throw new AssertionError();
	}

	/** Inventory Tweaks' Box Grid, over the screen's background and under the window's slots, the carried item and tooltips. */
	@Inject(method = "extractContents", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;pushMatrix()Lorg/joml/Matrix3x2fStack;", ordinal = 0))
	private void essentials$grid(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		InventoryTweaks.drawGrid(this, graphics, mouseX, mouseY);
	}

	/** A click on the Box Grid isn't outside the window (it would throw what's on the cursor). */
	@Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
	private void essentials$gridInside(double mx, double my, int xo, int yo, CallbackInfoReturnable<Boolean> cir) {
		if (InventoryTweaks.gridCovers(mx, my)) cir.setReturnValue(false);
	}

	/** Tooltips' icon on a shulker box carried on the cursor, as on one in a slot. */
	@Inject(method = "extractFloatingItem", at = @At("TAIL"))
	private void essentials$carriedIcon(GuiGraphicsExtractor graphics, ItemStack carried, int x, int y, @Nullable String itemCount, CallbackInfo ci) {
		Tooltips tooltips = Modules.active(Tooltips.class);
		if (tooltips != null && tooltips.slotIcons() && ItemInfo.isShulkerBox(carried)) Tooltips.drawIcon(graphics, carried, x, y);
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void essentials$press(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		InventoryTweaks.beforeInput();
		if (InventoryTweaks.gridPress(this, event.x(), event.y(), event.button())) {
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

	/** A key in the screen (a hotbar number, drop, closing it): Scroll Move's waiting items go first. */
	@Inject(method = "keyPressed", at = @At("HEAD"))
	private void essentials$key(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		InventoryTweaks.beforeInput();
	}

	/** Any click in the screen, yours or a feature's: Scroll Move's waiting items go first. */
	@Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("HEAD"))
	private void essentials$beforeClick(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
		InventoryTweaks.beforeInput();
	}

	/** Slots show Scroll Move's items where they're going (see ScrollBatch). */
	@ModifyExpressionValue(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0))
	private ItemStack essentials$shownSlot(ItemStack stack, @Local(argsOnly = true) Slot slot) {
		return InventoryTweaks.shownIn(this, slot, stack);
	}

	@ModifyExpressionValue(method = "extractTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack essentials$shownTooltip(ItemStack stack) {
		Slot hovered = hoveredSlot;
		return hovered == null ? stack : InventoryTweaks.shownIn(this, hovered, stack);
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
	public void essentials$clickNoSlot(int button, ContainerInput input) {
		slotClicked(null, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, button, input);
	}
}
