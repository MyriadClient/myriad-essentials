package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.Ticks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Scroll Move's items, shown where they go at once and moved together. Each notch of the wheel used to be three clicks
 * on its own (pick the stack up, drop one, put the rest back), so fast scrolling sent dozens a second, more than 2b2t
 * takes (it drops you on the 80th click in about 4 seconds), each clicking on a view its answers hadn't caught up with.
 * Now a notch only adds to what's moving from one slot to another (a notch back takes one off), the slots show the
 * result, and the moves go out in one tick once the wheel rests, as few clicks as they can be: half the stack picked up
 * where that's nearer, the rest one at a time. Clicking, a key or closing the screen sends them first.
 */
final class ScrollBatch {
	/** Ticks of a still wheel before the moves go. */
	private static final int REST_TICKS = 3;

	/** {@code count} of {@code like} from menu slot {@code from} to {@code to}. */
	private static final class Move {
		final int from, to;
		final ItemStack like;
		int count;

		Move(int from, int to, ItemStack like) {
			this.from = from;
			this.to = to;
			this.like = like.copyWithCount(1);
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final List<Move> moves = new ArrayList<>();
	private @Nullable InventoryTweaks.ScreenAccess screen;
	private long lastAt;
	private boolean sending;

	boolean pending() {
		return !moves.isEmpty();
	}

	/** One of {@code like} from {@code from} to {@code to} in {@code screen}, on top of what's waiting. */
	void add(InventoryTweaks.ScreenAccess screen, Slot from, Slot to, ItemStack like) {
		if (this.screen != screen) {
			send(true);
			this.screen = screen;
		}
		lastAt = Ticks.current();
		for (Iterator<Move> it = moves.iterator(); it.hasNext(); ) {
			Move m = it.next();
			if (m.from == to.index && m.to == from.index && ItemStack.isSameItemSameComponents(m.like, like)) {
				// Back the way it came: one fewer to move.
				if (--m.count <= 0) it.remove();
				return;
			}
		}
		for (Move m : moves) {
			if (m.from == from.index && m.to == to.index && ItemStack.isSameItemSameComponents(m.like, like)) {
				m.count++;
				return;
			}
		}
		Move m = new Move(from.index, to.index, like);
		m.count = 1;
		moves.add(m);
	}

	/** What {@code slot} of {@code menu} shows: what it holds, with what's waiting to move out of and into it. */
	ItemStack shown(AbstractContainerMenu menu, Slot slot, ItemStack real) {
		if (moves.isEmpty() || screen == null || screen.essentials$menu() != menu) return real;
		ItemStack shown = real;
		for (Move m : moves) {
			if (m.from == slot.index) shown = shown.copyWithCount(shown.getCount() - m.count);
			else if (m.to == slot.index) shown = shown.isEmpty() ? m.like.copyWithCount(m.count) : shown.copyWithCount(shown.getCount() + m.count);
		}
		return shown.getCount() <= 0 ? ItemStack.EMPTY : shown;
	}

	/** Each tick: once the wheel has rested, the moves go. */
	void tick() {
		if (moves.isEmpty()) return;
		if (screen == null || mc.player == null || screen.essentials$menu() != mc.player.containerMenu) {
			// The screen went without us (the server closed it): nothing moved, so nothing's lost.
			clear();
			return;
		}
		if (Ticks.current() - lastAt >= REST_TICKS) send(false);
	}

	/**
	 * Sends what's waiting, as the click budget and the server's rules allow. {@code now}: before a click, a key or the
	 * screen closing, whatever doesn't fit is dropped (the slots then show what they hold); otherwise it waits a tick.
	 */
	void send(boolean now) {
		if (moves.isEmpty() || sending) return;
		if (screen == null || mc.player == null || screen.essentials$menu() != mc.player.containerMenu || !mc.player.containerMenu.getCarried().isEmpty()) {
			clear();
			return;
		}
		sending = true;
		try {
			AbstractContainerMenu menu = screen.essentials$menu();
			for (Iterator<Move> it = moves.iterator(); it.hasNext(); ) {
				Move m = it.next();
				int[] plan = plan(menu, m);
				if (plan == null) {
					it.remove();
					continue;
				}
				if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, plan[0]) || !Myriad.inventory().safeToClick() || !Myriad.inventory().prepareClick()) {
					if (now) clear();
					return;
				}
				click(menu, m, plan[1]);
				it.remove();
			}
		} finally {
			sending = false;
		}
		if (moves.isEmpty()) screen = null;
	}

	/** {clicks, how many to move} for {@code m} as the menu stands, or null if none can go. */
	private static int @Nullable [] plan(AbstractContainerMenu menu, Move m) {
		if (m.from >= menu.slots.size() || m.to >= menu.slots.size()) return null;
		ItemStack from = menu.getSlot(m.from).getItem(), to = menu.getSlot(m.to).getItem();
		if (from.isEmpty() || !ItemStack.isSameItemSameComponents(from, m.like)) return null;
		if (!to.isEmpty() && !ItemStack.isSameItemSameComponents(to, from)) return null;
		Slot target = menu.getSlot(m.to);
		if (!target.mayPlace(from) || !menu.getSlot(m.from).mayPickup(Minecraft.getInstance().player)) return null;
		int room = target.getMaxStackSize(from) - to.getCount();
		int k = Math.min(m.count, Math.min(from.getCount(), room));
		if (k <= 0) return null;
		return new int[]{clicks(from.getCount(), k), k};
	}

	/**
	 * Clicks to move {@code k} of a stack of {@code n}: all of it, picked up and put down (2); at least the half a
	 * right-click picks up, that put down (2) and the rest one at a time with the stack picked up and put back
	 * (2 + rest); otherwise the stack picked up, one at a time, and put back (k + 2).
	 */
	static int clicks(int n, int k) {
		if (k == n) return 2;
		int half = (n + 1) / 2;
		if (k >= half) return k == half ? 2 : 2 + (k - half) + 2;
		return k + 2;
	}

	private void click(AbstractContainerMenu menu, Move m, int k) {
		Slot from = menu.getSlot(m.from), to = menu.getSlot(m.to);
		int n = from.getItem().getCount(), half = (n + 1) / 2;
		if (k == n) {
			screen.essentials$click(from, 0, ContainerInput.PICKUP);
			screen.essentials$click(to, 0, ContainerInput.PICKUP);
			return;
		}
		int ones = k;
		if (k >= half) {
			// The bigger half, put down whole.
			screen.essentials$click(from, 1, ContainerInput.PICKUP);
			screen.essentials$click(to, 0, ContainerInput.PICKUP);
			ones = k - half;
			if (ones == 0) return;
		}
		screen.essentials$click(from, 0, ContainerInput.PICKUP);
		for (int i = 0; i < ones; i++) screen.essentials$click(to, 1, ContainerInput.PICKUP);
		screen.essentials$click(from, 0, ContainerInput.PICKUP);
	}

	void clear() {
		moves.clear();
		screen = null;
	}
}
