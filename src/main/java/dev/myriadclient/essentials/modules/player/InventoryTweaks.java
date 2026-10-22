package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerScreenEvent;
import dev.myriadclient.api.job.Steps;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.util.ItemInfo;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Right-click a shulker box (or ender chest) in your inventory to open it on the spot: it's placed beside you, opened,
 * and broken and picked back up when you close it. Ender chests need a Silk Touch pickaxe in the hotbar, or breaking
 * them would only drop obsidian.
 */
public class InventoryTweaks extends Module {
	private final BoolSetting shulkers = sgGeneral.bool("Instant Shulker").description("Right-click a shulker box in your inventory to open it.").defaultValue(true).build();
	private final BoolSetting enderChests = sgGeneral.bool("Instant Ender Chest").description("Right-click an ender chest in your inventory to open it (needs a Silk Touch pickaxe to pick it back up).")
		.defaultValue(true).build();

	/** How long to wait for you to close the box, in ticks: long enough to sort a whole box by hand. */
	private static final int BROWSE_TICKS = 20 * 60 * 30;

	public InventoryTweaks() {
		super(Tags.PLAYER, "Inventory Tweaks", "Open shulker boxes and ender chests straight from your inventory.");
	}

	@Subscribe
	private void onClick(ContainerScreenEvent.Click e) {
		if (onSlotClick(e.slot(), e.button(), e.input())) e.cancel();
	}

	/** Each slot click in an inventory screen. Returns true to swallow the click. */
	private boolean onSlotClick(Slot slot, int button, ContainerInput input) {
		if (Myriad.jobs().isBusy(this) || input != ContainerInput.PICKUP || button != 1 || slot == null || !slot.hasItem() || !inGame()) return false;
		if (mc.player.containerMenu != mc.player.inventoryMenu || mc.player.isCreative() || slot.container != mc.player.getInventory()) return false;
		if (!mc.player.inventoryMenu.getCarried().isEmpty()) return false;
		ItemStack stack = slot.getItem();
		boolean shulker = shulkers.get() && ItemInfo.isShulkerBox(stack);
		boolean ender = enderChests.get() && stack.is(Items.ENDER_CHEST);
		if (!shulker && !ender) return false;
		int index = slot.getContainerSlot();
		if (index < 0 || index >= 36) return false;

		// Put down, opened, and picked back up when you close it (or when it can't open, saying why).
		Myriad.jobs().start(this, Steps.inItem(index, view -> Steps.untilClosed(view, BROWSE_TICKS))
			.named(ender ? "Ender chest" : "Shulker box")
			.onFailed(why -> warn(capitalized(why.detail().isEmpty() ? why.toString() : why.detail()))));
		return true;
	}

	private static String capitalized(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
