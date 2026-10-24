package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.util.Slots;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Keeps hotbar stacks topped up from the rest of your inventory: a stack running low is refilled with the same item,
 * and a slot you've just used up gets the same item back. Every slot that needs it at once (as far as the packet budget
 * goes; the rest the next tick), the first tick there's no screen open, so it never fights you rearranging your
 * inventory. Sprinting doesn't hold it up (core gets a click past Grim in the same tick); where Grim judges the keys you
 * hold (newer versions, not 2b2t), it waits for a moment you aren't pressing any.
 */
public class Replenish extends Module {
	private final IntSetting threshold = sgGeneral.integer("Threshold").description("Refill a stack when it drops to this percent of a full one.")
		.defaultValue(25).range(1, 99).build();
	private final BoolSetting mainHand = sgGeneral.bool("Main Hand").description("Refill the stack you're holding.").defaultValue(true).build();
	private final BoolSetting offHand = sgGeneral.bool("Off Hand").description("Refill the off hand (crystals, golden apples).").defaultValue(true).build();
	private final BoolSetting hotbar = sgGeneral.bool("Hotbar").description("Refill the other hotbar slots.").defaultValue(true).build();

	/** What each hotbar slot (and the off hand, last) held last tick, so an emptied slot can get the same item back. */
	private final ItemStack[] last = new ItemStack[10];

	public Replenish() {
		super(Tags.PLAYER, "Replenish", "Refills hotbar stacks from your inventory.");
		formerNames("Stack Replenish");
	}

	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) saved.rename("General", "Offhand", "Off Hand");
	}

	@Override
	protected void onEnable() {
		Arrays.fill(last, ItemStack.EMPTY);
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame() || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return;
		Inventory inv = mc.player.getInventory();
		// With a screen open, slots empty because you're moving things: just keep track. And while a click would have
		// to wait (remembering what each slot held, so one used up meanwhile is still refilled then).
		boolean clicks = mc.gui.screen() == null && Myriad.inventory().safeToClick();
		int selected = inv.getSelectedSlot();
		for (int i = 0; i < last.length; i++) {
			int slot = i < 9 ? i : Slots.OFF_HAND;
			boolean wanted = slot == Slots.OFF_HAND ? offHand.get() : slot == selected ? mainHand.get() : hotbar.get();
			if (clicks && wanted) refill(slot, inv.getItem(slot), last[i]);
			last[i] = inv.getItem(slot).copy();
		}
	}

	/** Refills {@code slot} if it's low or was just emptied. Returns true if it sent a refill. */
	private boolean refill(int slot, ItemStack stack, ItemStack before) {
		if (stack.isEmpty()) {
			// Used up: put the same item back from the inventory.
			if (before == null || before.isEmpty() || !before.isStackable()) return false;
			int from = source(before);
			if (from < 0) return false;
			return slot == Slots.OFF_HAND ? Myriad.inventory().swapWithOffhand(from) : Myriad.inventory().moveToHotbar(from, slot);
		}
		if (!stack.isStackable() || stack.getCount() * 100 > stack.getMaxStackSize() * threshold.get()) return false;
		int from = source(stack);
		return from >= 0 && Myriad.inventory().merge(from, slot);
	}

	/** A main-inventory slot (9-35) holding the same item, or -1. */
	private int source(ItemStack like) {
		Inventory inv = mc.player.getInventory();
		for (int i = 9; i < 36; i++) if (ItemStack.isSameItemSameComponents(inv.getItem(i), like)) return i;
		return -1;
	}
}
