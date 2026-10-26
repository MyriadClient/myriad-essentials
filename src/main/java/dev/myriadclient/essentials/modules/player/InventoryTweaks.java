package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.event.events.ContainerScreenEvent;
import dev.myriadclient.api.event.events.ItemTooltipEvent;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.ItemInfo;
import dev.myriadclient.essentials.util.Layouts;
import dev.myriadclient.essentials.util.Menus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Mod + right-click a shulker box (or ender chest) in your inventory, or in a chest (barrel, shulker box, ender chest,
 * hopper, dispenser) you have open, to put it down on the spot, above you or beside you: from your inventory with
 * nothing else on core's swap bar it opens too; otherwise it only gets a tab there, the screen you're in staying up, so
 * several can be put down in a row and opened from the bar. It stands while you switch, and is broken and picked back
 * up once you close a screen yourself, into your inventory or the container it came from (any free slot). A plain right-click stays vanilla's (half a stack). Ender chests
 * need a Silk Touch pickaxe, or breaking them would only drop obsidian.
 * Remote Ender Chest holds an ender chest open after you close it (Containers.hold), from anywhere: the inventory key
 * shows it again.
 * <p>
 * And the mouse in any inventory screen: Shift-drag shift-clicks every slot you pass, a left drag that starts on a slot
 * of the item you're carrying gathers every matching slot it passes into your stack, and the wheel moves one item at a
 * time to the other inventory or back, and a middle-click sorts what's under the mouse: the container's slots, or your
 * inventory (not the hotbar, which stays as you keep it), shown sorted at once as Auto Sort does it, for sorting by
 * hand when you don't want every container sorted as it opens. Vanilla's own drags (spreading a stack, one item per
 * slot) are left alone. Every
 * click goes through the screen as yours would, and only while the server takes clicks (not while you walk on Grim):
 * a multi-click move is never left half done. Applied by this addon's ContainerScreenMixin.
 */
public class InventoryTweaks extends Module {
	private final BoolSetting shulkers = sgGeneral.bool("Instant Shulker").description("Mod + right-click a shulker box in your inventory, or in a chest you have open, "
		+ "to put it down and add it to the swap bar (opened too, from your inventory with nothing else there).").defaultValue(true).build();
	private final BoolSetting enderChests = sgGeneral.bool("Instant Ender Chest").description("Mod + right-click an ender chest in your inventory, or in a chest you have "
		+ "open, to put it down as a shulker box is (needs a Silk Touch pickaxe to pick it back up).").defaultValue(true).build();
	private final BoolSetting remoteEnderChest = sgGeneral.bool("Remote Ender Chest").description("Keep an ender chest open after you close it, from anywhere, "
		+ "and open it again with your inventory key. One opened from your inventory is picked back up and stays open.")
		.defaultValue(true).onChanged(on -> {
			if (!on) letGo();
		}).build();
	private final EnumSetting<Containers.NoWayBack> noWayBack = sgGeneral.oneOf("No Way Back", Containers.NoWayBack.REFUSE).description("A box from a "
		+ "container that can't be opened again to put it back (your remote ender chest far away, with no other ender chest in reach; any one will do): "
		+ "Refuse leaves it there; Ender Chest puts one down from your inventory to put it back through, and picks it up again; Inventory lets it "
		+ "come back into your inventory.").visible(() -> shulkers.get() || enderChests.get())
		.onChanged(v -> {
			if (Myriad.isReady()) Myriad.containers().noWayBack(this, v);
		}).build();
	// Shown everywhere, as core's Air Place is: 2b2t takes it and current Grim doesn't.
	private final BoolSetting airPlace = sgGeneral.bool("Air Place").description("Put the box above your head with nothing there to click against, "
		+ "through the off hand. 2b2t takes it; current Grim refuses it. Off, it goes above your head only under a ceiling, else beside you.")
		.visible(() -> shulkers.get() || enderChests.get()).build();

	private final SettingGroup sgMouse = settings.group("Mouse");
	private final BoolSetting shiftDrag = sgMouse.bool("Shift Drag").description("Hold Shift and drag with the left button to shift-click every slot you pass.")
		.defaultValue(true).build();
	private final BoolSetting dragCollect = sgMouse.bool("Drag Collect").description("Carrying a stack, start a left drag on a slot of the same item: every matching slot you pass "
		+ "joins your stack, until it's full. A drag started anywhere else spreads the stack as vanilla does.").defaultValue(true).build();
	private final BoolSetting scrollMove = sgMouse.bool("Scroll Move").description("Scroll down over an item to move one to the other inventory, up to bring one back from it. "
		+ "In your own inventory, between the hotbar and the rest.").defaultValue(true).build();

	private final BoolSetting middleSort = sgMouse.bool("Middle Sort").description("Middle-click over a container's slots to sort them, or over your "
		+ "inventory to sort it (the hotbar stays as you keep it). It shows sorted at once; a click of yours stops it.").defaultValue(true).build();
	private final EnumSetting<Layouts.Order> sortOrder = sgMouse.oneOf("Sort Order", Layouts.Order.ITEM).description("Item: the game's own "
		+ "order, close to the creative tabs. Name: A to Z. Count: what you have most of first.").visible(middleSort::get).build();

	/** How long the box may take to open once clicked. */
	private static final int OPEN_TICKS = 40;

	/** What the mixin gives this module of a container screen. */
	public interface ScreenAccess {
		AbstractContainerMenu essentials$menu();

		/** Clicks {@code slot} as your own click in the screen would. */
		void essentials$click(Slot slot, int button, ContainerInput input);

		/** Ends vanilla's drag (spreading the carried stack) without it placing anything on release. */
		void essentials$endVanillaDrag();
	}

	// The drag in progress: where the button went down, with what, and the slots it has handled.
	private @Nullable Slot pressed;
	private int pressButton = -1;
	private boolean pressShift, pressMod, collectStart, collecting;
	private final Set<Slot> handled = Collections.newSetFromMap(new IdentityHashMap<>());

	public InventoryTweaks() {
		super(Tags.PLAYER, "Inventory Tweaks", "Open shulker boxes from your inventory, and move items with drags and the wheel.");
	}

	/** The ender chest held for Remote Ender Chest (its block may be gone). */
	private Containers.@Nullable View held;

	@Override
	protected void onEnable() {
		// The swap bar over container screens, themed or not: boxes and the held ender chest are switched between there.
		Myriad.containers().swapBar(this, true);
		Myriad.containers().noWayBack(this, noWayBack.get());
	}

	@Override
	protected void onDisable() {
		Myriad.containers().swapBar(this, false);
		release();
		letGo();
	}

	/**
	 * An ender chest opened (by hand, or put down from your inventory) is held: core opens it once more over itself, after
	 * which the server keeps it open anywhere; one put down for you is picked back up when you close it and stays open.
	 */
	@Subscribe
	private void onOpened(ContainerEvent.Opened e) {
		Containers.View v = e.view();
		if (remoteEnderChest.get() && inGame() && v.pos() != null && mc.level.getBlockState(v.pos()).is(Blocks.ENDER_CHEST) && Myriad.containers().hold(v)) held = v;
	}

	private void letGo() {
		if (held != null && Myriad.containers().held().orElse(null) == held) Myriad.containers().release();
		held = null;
	}

	@Subscribe
	private void onClick(ContainerScreenEvent.Click e) {
		if (onSlotClick(e.slot(), e.button(), e.input())) e.cancel();
	}

	/** Each slot click in an inventory screen. Returns true to swallow the click. */
	private boolean onSlotClick(Slot slot, int button, ContainerInput input) {
		if (input != ContainerInput.PICKUP || button != 1 || slot == null || !slot.hasItem() || !inGame() || !pressMod) return false;
		AbstractContainerMenu menu = mc.player.containerMenu;
		if (mc.player.isCreative() || !menu.getCarried().isEmpty()) return false;
		// Your own inventory's slots, or a storage container's (opened from a block, to be opened again once it's back).
		boolean inventory = menu == mc.player.inventoryMenu;
		Containers.View from = inventory ? null : Myriad.containers().current().filter(v -> v.pos() != null && v.handler() == menu).orElse(null);
		if (inventory ? slot.container != mc.player.getInventory() : from == null || !storage(menu)) return false;
		if (Myriad.jobs().isBusy(this)) return false;
		ItemStack stack = slot.getItem();
		if (!isBox(stack)) return false;
		int index = slot.getContainerSlot();
		if (inventory && (index < 0 || index >= 36)) return false;

		// Put down (opened too from your inventory with nothing else on the swap bar, else only given a tab there), and
		// picked back up once you close a screen yourself (core keeps it standing while you switch between containers on
		// the bar), or when it can't go down, saying why.
		standing.removeIf(b -> !stands(b));
		boolean open = inventory && standing.isEmpty() && Myriad.containers().held().isEmpty();
		Placement.Options options = Placement.Options.forServer().withAirPlace(airPlace.get());
		Containers c = Myriad.containers();
		CompletableFuture<Containers.ItemSession> down = inventory ? open ? c.openItem(this, index, OPEN_TICKS, options) : c.putDownItem(this, index, OPEN_TICKS, options)
			: c.putDownItem(this, from, slot.index, OPEN_TICKS, options);
		down.whenComplete((session, t) -> {
			if (t != null) {
				Why why = Why.of(t);
				if (!why.is(Why.CANCELLED) && !why.is(Why.OWNER_OFF) && !why.is(Why.LEFT_WORLD)) warn(capitalized(why.detail().isEmpty() ? why.toString() : why.detail()));
				return;
			}
			standing.add(session);
			session.finishWhenClosed();
		});
		return true;
	}

	/** Boxes this put down, until they're back. */
	private final List<Containers.ItemSession> standing = new ArrayList<>();

	private boolean stands(Containers.ItemSession box) {
		if (box.where() == null) return false;
		BlockState state = mc.level.getBlockState(box.where());
		return state.getBlock() instanceof ShulkerBoxBlock || state.is(Blocks.ENDER_CHEST);
	}

	private boolean isBox(ItemStack stack) {
		return shulkers.get() && ItemInfo.isShulkerBox(stack) || enderChests.get() && stack.is(Items.ENDER_CHEST);
	}

	/** With the Mod key held, a box's tooltip says a right-click puts it down. */
	@Subscribe
	private void onTooltip(ItemTooltipEvent e) {
		if (!mc.isSameThread() || !inGame() || mc.player.isCreative() || e.lines().isEmpty() || !isBox(e.stack())) return;
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>) || !Myriad.ui().theme().modKey.get().isDown()) return;
		e.lines().set(0, e.lines().getFirst().copy().append(Component.literal(" (Right Click Place)").withStyle(ChatFormatting.GRAY)));
	}

	/** Plain storage, whose slots a box can be in and go back to. */
	private static boolean storage(AbstractContainerMenu menu) {
		return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
	}

	private static String capitalized(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// The mouse, from ContainerScreenMixin: each through Modules.guard, so a failure turns the module off rather than
	// crashing the screen.

	private static @Nullable InventoryTweaks active(ScreenAccess screen) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		if (m == null || !m.inGame() || screen instanceof CreativeModeInventoryScreen || Myriad.jobs().isBusy(m)) return null;
		return m;
	}

	/** A mouse button went down over {@code slot} (null if none). */
	public static void press(ScreenAccess screen, @Nullable Slot slot, int button, boolean shift, int modifiers) {
		InventoryTweaks m = active(screen);
		if (m == null) return;
		Modules.guard(m, () -> {
			m.release();
			m.pressed = slot;
			m.pressButton = button;
			m.pressShift = shift;
			m.pressMod = Myriad.ui().theme().modKey.get().in(modifiers);
			ItemStack carried = screen.essentials$menu().getCarried();
			m.collectStart = button == 0 && !carried.isEmpty() && slot != null && slot.hasItem() && ItemStack.isSameItemSameComponents(slot.getItem(), carried);
		});
	}

	/** The mouse moved with a button down, over {@code slot}: true if this took the drag (vanilla then does nothing). */
	public static boolean drag(ScreenAccess screen, @Nullable Slot slot, boolean shift) {
		InventoryTweaks m = active(screen);
		return m != null && slot != null && m.pressButton == 0 && Modules.guard(m, () -> m.onDrag(screen, slot, shift), false);
	}

	/** The button came up: the drag is over. */
	public static void released(ScreenAccess screen) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		if (m != null) Modules.guard(m, m::release);
	}

	/** A mouse button went down over {@code slot}: true if it was a middle-click this sorted with (vanilla then does nothing). */
	public static boolean middleClick(ScreenAccess screen, @Nullable Slot slot, int button) {
		InventoryTweaks m = active(screen);
		return m != null && slot != null && button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && m.middleSort.get() && Modules.guard(m, () -> m.sort(screen, slot), false);
	}

	/** The wheel turned over {@code slot} and vanilla didn't use it (a bundle does): true if this did. */
	public static boolean scroll(ScreenAccess screen, @Nullable Slot slot, double amount) {
		InventoryTweaks m = active(screen);
		return m != null && slot != null && amount != 0 && m.scrollMove.get() && Modules.guard(m, () -> m.onScroll(screen, slot, amount < 0), false);
	}

	/**
	 * Sorts the side of the screen {@code slot} is on: your main inventory, or the container's own slots (chests, barrels,
	 * shulker boxes, dispensers, hoppers). False, leaving the click to vanilla, with something on the cursor or over
	 * anything else.
	 */
	private boolean sort(ScreenAccess screen, Slot slot) {
		AbstractContainerMenu menu = screen.essentials$menu();
		if (!menu.getCarried().isEmpty() || mc.player.isCreative()) return false;
		boolean yours = slot.container == mc.player.getInventory();
		boolean storage = menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof DispenserMenu || menu instanceof HopperMenu;
		if (!yours && !storage) return false;
		// Your hotbar stays as you keep it; only the main inventory is sorted.
		if (yours && slot.getContainerSlot() < 9 || yours && slot.getContainerSlot() >= 36) return false;
		int[] slots = yours ? Menus.playerSlots(menu, false) : Menus.containerSlots(menu);
		List<ItemStack> layout = Layouts.sorted(menu, slots, sortOrder.get());
		if (Layouts.holds(menu, slots, layout)) return true;
		Containers.Arrangement arrangement = Containers.Arrangement.of(slots, layout);
		CompletableFuture<Boolean> done;
		if (menu == mc.player.inventoryMenu) {
			done = Myriad.inventory().arrange(arrangement);
		} else {
			var view = Myriad.containers().current().filter(v -> v.handler() == menu);
			if (view.isEmpty()) return false;
			done = view.get().arrange(arrangement);
		}
		done.whenComplete((ok, t) -> {
			if (t != null && !(t.getMessage() != null && t.getMessage().startsWith("You clicked")) && Menus.isOpen(menu)) warn("Stopped sorting: " + t.getMessage());
		});
		return true;
	}

	private void release() {
		pressed = null;
		pressButton = -1;
		collectStart = collecting = false;
		handled.clear();
	}

	private boolean onDrag(ScreenAccess screen, Slot slot, boolean shift) {
		AbstractContainerMenu menu = screen.essentials$menu();
		ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			// Shift-drag: vanilla shift-clicked the first slot on the press; each slot after it is shift-clicked once.
			if (!shiftDrag.get() || !pressShift || !shift) return false;
			if (pressed != null) handled.add(pressed);
			if (!slot.hasItem() || !slot.mayPickup(mc.player) || !handled.add(slot)) return true;
			if (!canClick()) {
				handled.remove(slot);
				return true;
			}
			screen.essentials$click(slot, 0, ContainerInput.QUICK_MOVE);
			return true;
		}
		if (!collecting) {
			// Still on the first slot, or a drag that didn't start on the carried item: vanilla's spread.
			if (!dragCollect.get() || !collectStart || slot == pressed) return false;
			if (!slot.hasItem() || !ItemStack.isSameItemSameComponents(slot.getItem(), carried)) {
				collectStart = false;
				return false;
			}
			if (!canClick()) return true;
			collecting = true;
			screen.essentials$endVanillaDrag();
			collect(screen, pressed);
		}
		if (handled.contains(slot) || !canClick()) return true;
		collect(screen, slot);
		return true;
	}

	/**
	 * Adds {@code slot}'s items to the carried stack: put the stack down on them, then pick the lot up. A slot whose
	 * items wouldn't all fit is left as it is (putting down would leave fewer in your hand, not more).
	 */
	private void collect(ScreenAccess screen, @Nullable Slot slot) {
		if (slot == null || !handled.add(slot)) return;
		ItemStack carried = screen.essentials$menu().getCarried(), in = slot.getItem();
		if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, carried) || !slot.mayPickup(mc.player) || !slot.mayPlace(carried)) return;
		if (carried.getCount() + in.getCount() > Math.min(carried.getMaxStackSize(), slot.getMaxStackSize(carried))) return;
		screen.essentials$click(slot, 0, ContainerInput.PICKUP);
		screen.essentials$click(slot, 0, ContainerInput.PICKUP);
	}

	/** One item from {@code slot} to the other inventory ({@code away}), or one from there into {@code slot}. */
	private boolean onScroll(ScreenAccess screen, Slot slot, boolean away) {
		AbstractContainerMenu menu = screen.essentials$menu();
		if (!menu.getCarried().isEmpty()) return false;
		ItemStack item = slot.getItem();
		if (item.isEmpty()) return false;
		Slot from, to;
		if (away) {
			if (!slot.mayPickup(mc.player)) return false;
			from = slot;
			to = target(menu, slot, item);
		} else {
			if (item.getCount() >= slot.getMaxStackSize(item) || !slot.mayPlace(item)) return false;
			from = source(menu, slot, item);
			to = slot;
		}
		if (from == null || to == null) return true;
		if (!canClick()) return true;
		// Pick the stack up, drop one where it goes, and put the rest back (a single item needs no putting back).
		boolean single = from.getItem().getCount() == 1;
		screen.essentials$click(from, 0, ContainerInput.PICKUP);
		screen.essentials$click(to, single ? 0 : 1, ContainerInput.PICKUP);
		if (!single) screen.essentials$click(from, 0, ContainerInput.PICKUP);
		return true;
	}

	/** Where one of {@code item} from {@code slot} goes on the other side: a stack of it with room, else an empty slot. */
	private @Nullable Slot target(AbstractContainerMenu menu, Slot slot, ItemStack item) {
		Slot empty = null;
		for (Slot s : menu.slots) {
			if (!otherSide(menu, slot, s) || !s.mayPlace(item)) continue;
			ItemStack there = s.getItem();
			if (there.isEmpty()) {
				if (empty == null) empty = s;
			} else if (ItemStack.isSameItemSameComponents(there, item) && there.getCount() < s.getMaxStackSize(there)) {
				return s;
			}
		}
		return empty;
	}

	/** Where one of {@code item} for {@code slot} comes from on the other side. */
	private @Nullable Slot source(AbstractContainerMenu menu, Slot slot, ItemStack item) {
		for (Slot s : menu.slots) {
			// Only slots you could put it back in: taking from a crafting or furnace output would craft or take the product.
			if (otherSide(menu, slot, s) && s.hasItem() && ItemStack.isSameItemSameComponents(s.getItem(), item) && s.mayPickup(mc.player) && s.mayPlace(item)) return s;
		}
		return null;
	}

	/**
	 * Whether {@code other} is across from {@code slot}: your inventory and the container you opened, or in your own
	 * inventory screen the hotbar and the rest. Only your 36 inventory slots count on your side (not armour or the off
	 * hand), and the crafting grid in your own inventory isn't a side.
	 */
	private boolean otherSide(AbstractContainerMenu menu, Slot slot, Slot other) {
		var inventory = mc.player.getInventory();
		boolean mine = slot.container == inventory, theirs = other.container == inventory;
		if ((mine && slot.getContainerSlot() >= 36) || (theirs && other.getContainerSlot() >= 36)) return false;
		if (menu instanceof InventoryMenu) {
			if (!mine || !theirs) return false;
			return (slot.getContainerSlot() < 9) != (other.getContainerSlot() < 9);
		}
		return mine != theirs;
	}

	/** Whether a click can go now, as the server takes it (a held click would leave a multi-click move half done). */
	private static boolean canClick() {
		return Myriad.inventory().safeToClick() && Myriad.inventory().prepareClick();
	}
}
