package dev.myriadclient.essentials.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** Menu helpers: whether a menu is open, and which of its slots are the player's and which its own. */
public final class Menus {
	private Menus() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Whether {@code menu} is the one open (the player's own inventory counts while no other is). */
	public static boolean isOpen(AbstractContainerMenu menu) {
		return mc().player != null && mc().player.containerMenu == menu;
	}

	/** Menu slot ids of the player's main inventory (9-35), then the hotbar (0-8) if {@code hotbar}. */
	public static int[] playerSlots(AbstractContainerMenu menu, boolean hotbar) {
		int[] ids = new int[hotbar ? 36 : 27];
		int n = 0;
		for (int index = 9; index < 36; index++) ids[n++] = playerSlot(menu, index);
		if (hotbar) for (int index = 0; index < 9; index++) ids[n++] = playerSlot(menu, index);
		return ids;
	}

	/** The menu slot id showing inventory index {@code index} (0-35, 36-40 in the player's own menu), or -1. */
	private static int playerSlot(AbstractContainerMenu menu, int index) {
		var inv = mc().player.getInventory();
		for (Slot s : menu.slots) if (s.container == inv && s.getContainerSlot() == index) return s.index;
		return -1;
	}

	/** The menu's own slots: those not showing the player's inventory. */
	public static int[] containerSlots(AbstractContainerMenu menu) {
		var inv = mc().player.getInventory();
		return menu.slots.stream().filter(s -> s.container != inv).mapToInt(s -> s.index).toArray();
	}
}
