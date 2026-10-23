package dev.myriadclient.essentials.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Target layouts worked out from what's there. */
public final class Layouts {
	public enum Order {
		/** The game's own item order (close to the creative tabs): blocks by kind, then tools, food, and so on. */
		ITEM,
		/** By name, A to Z. */
		NAME,
		/** The items you have most of first. */
		COUNT
	}

	private Layouts() {
	}

	/**
	 * The slots {@code slots} sorted: every kind of item (same item and components) merged into as few stacks as it
	 * fills, in {@code order}, from the first slot on; the rest empty.
	 */
	public static List<ItemStack> sorted(AbstractContainerMenu menu, int[] slots, Order order) {
		List<ItemStack> kinds = new ArrayList<>();
		List<int[]> totals = new ArrayList<>();
		for (int s : slots) {
			ItemStack stack = menu.getSlot(s).getItem();
			if (stack.isEmpty()) continue;
			int k = 0;
			while (k < kinds.size() && !ItemStack.isSameItemSameComponents(kinds.get(k), stack)) k++;
			if (k == kinds.size()) {
				kinds.add(stack.copyWithCount(1));
				totals.add(new int[1]);
			}
			totals.get(k)[0] += stack.getCount();
		}
		Integer[] idx = new Integer[kinds.size()];
		for (int i = 0; i < idx.length; i++) idx[i] = i;
		Comparator<Integer> byItem = Comparator.comparingInt(i -> BuiltInRegistries.ITEM.getId(kinds.get(i).getItem()));
		Comparator<Integer> byName = Comparator.comparing(i -> kinds.get(i).getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
		Comparator<Integer> c = switch (order) {
			case ITEM -> byItem.thenComparing(byName);
			case NAME -> byName.thenComparing(byItem);
			case COUNT -> Comparator.<Integer>comparingInt(i -> -totals.get(i)[0]).thenComparing(byItem);
		};
		// Same item, different components (enchantments, wear): the most worn last, then a stable order.
		c = c.thenComparingInt(i -> kinds.get(i).getDamageValue()).thenComparingInt(i -> ItemStack.hashItemAndComponents(kinds.get(i)));
		java.util.Arrays.sort(idx, c);
		List<ItemStack> layout = new ArrayList<>(slots.length);
		for (int i : idx) {
			ItemStack kind = kinds.get(i);
			int left = totals.get(i)[0];
			while (left > 0 && layout.size() < slots.length) {
				int count = Math.min(left, Math.min(kind.getMaxStackSize(), menu.getSlot(slots[layout.size()]).getMaxStackSize(kind)));
				layout.add(kind.copyWithCount(count));
				left -= count;
			}
		}
		while (layout.size() < slots.length) layout.add(ItemStack.EMPTY);
		return layout;
	}

	/** Whether {@code slots} already hold {@code layout} exactly (null entries: anything). */
	public static boolean holds(AbstractContainerMenu menu, int[] slots, List<ItemStack> layout) {
		for (int i = 0; i < slots.length; i++) {
			ItemStack want = layout.get(i);
			if (want != null && !ItemStack.matches(menu.getSlot(slots[i]).getItem(), want)) return false;
		}
		return true;
	}
}
