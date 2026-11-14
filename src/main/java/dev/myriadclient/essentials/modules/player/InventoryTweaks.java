package dev.myriadclient.essentials.modules.player;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.event.events.ContainerScreenEvent;
import dev.myriadclient.api.event.events.ItemTooltipEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.item.ItemInfo;
import dev.myriadclient.api.item.Slots;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.essentials.util.Layouts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingContainer;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Mod + right-click a shulker box (or ender chest) in your inventory, or in a chest (barrel, shulker box, ender chest,
 * hopper, dispenser) you have open, to put it down on the spot, above you or beside you: from your inventory with
 * nothing else on core's swap bar it opens too; otherwise it only gets a tab there, the screen you're in staying up, so
 * several can be put down in a row and opened from the bar. It stands while you switch, and is broken and picked back
 * up once you close a screen yourself, into your inventory or the container it came from (any free slot). A plain
 * right-click stays vanilla's (half a stack). Ender chests need a Silk Touch pickaxe, or breaking them would only drop
 * obsidian. Remote Ender Chest holds an ender chest open after you close it (Containers.hold), from anywhere: the
 * inventory key shows it again. Box Grid shows what a chest's shulker boxes hold beside and below its window (see
 * BoxGrid).
 * <p>
 * And the mouse in any inventory screen: Shift-drag shift-clicks every slot you pass, the wheel moves one item at a
 * time to the other inventory or back, and a middle-click sorts what's under the mouse: the container's slots, or your
 * inventory (not the hotbar, which stays as you keep it), shown sorted at once as Auto Sort does it, for sorting by
 * hand when you don't want every container sorted as it opens. Over a crafting grid a middle-click shares each item out
 * evenly among the slots holding it. Vanilla's own drags (spreading a stack, one item per slot) are left alone. Every
 * click goes through the screen as yours would, and only while the server takes clicks (not while you walk on Grim): a
 * multi-click move is never left half done. Applied by this addon's AbstractContainerScreenMixin.
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
	private final BoolSetting boxGrid = sgGeneral.bool("Box Grid").description("With a chest open, show what every shulker box in it holds, in "
		+ "panels beside and below the window (hover for tooltips, the wheel scrolls them a row at a time). Click one to put that box down and "
		+ "give it a tab on the swap bar, as Mod + right-click does.").defaultValue(true).build();

	private final SettingGroup sgMouse = settings.group("Mouse");
	private final BoolSetting shiftDrag = sgMouse.bool("Shift Drag").description("Hold Shift and drag with the left button to shift-click every slot you pass.")
		.defaultValue(true).build();
	private final BoolSetting scrollMove = sgMouse.bool("Scroll Move").description("Scroll down over an item to move one to the other inventory, up to bring one back from it. "
		+ "In your own inventory, between the hotbar and the rest.").defaultValue(true).build();

	private final BoolSetting middleSort = sgMouse.bool("Middle Sort").description("Middle-click over a container's slots to sort them, or over your "
		+ "inventory to sort it (the hotbar stays as you keep it). It shows sorted at once; a click of yours stops it.").defaultValue(true).build();
	private final EnumSetting<Layouts.Order> sortOrder = sgMouse.oneOf("Sort Order", Layouts.Order.ITEM).description("Item: the game's own "
		+ "order, close to the creative tabs. Name: A to Z. Count: what you have most of first.").visible(middleSort::get).build();
	private final BoolSetting middleBalance = sgMouse.bool("Middle Balance").description("Middle-click over a crafting grid to share each item out evenly "
		+ "among the slots holding it: a stack of 64 iron and eight single ingots become nine of 8.").defaultValue(true).build();

	/** How long the box may take to open once clicked. */
	private static final int OPEN_TICKS = 40;

	/** What the mixin gives this module of a container screen. */
	public interface ScreenAccess {
		AbstractContainerMenu essentials$menu();

		/** Clicks {@code slot} as your own click in the screen would. */
		void essentials$click(Slot slot, int button, ContainerInput input);

		/** A click on no slot: a drag's start and end ({@link ContainerInput#QUICK_CRAFT}). */
		void essentials$clickNoSlot(int button, ContainerInput input);

		/** The window: left, top, width, height, in GUI pixels. */
		int[] essentials$bounds();

	}

	/** A Box Grid panel where it's drawn: the chest slot its box came from, the box, and its 27 slots on the screen. */
	public record GridPanel(int box, ItemStack item, List<GridCell> cells) {}

	/**
	 * One slot of a Box Grid panel: its top left on the screen (GUI pixels, as a slot's), and whether it shows (wholly
	 * in view).
	 */
	public record GridCell(int x, int y, boolean shown) {}

	/** The chest a Box Grid is for. */
	public record GridChest(BlockPos pos) {}

	/**
	 * Someone else's Box Grid panels for a chest (see {@link #BOX_GRID}): what a press on a box's slot does, and what the
	 * panels show.
	 */
	public interface GridHandler {
		/**
		 * A press or a middle-click on a box's slot in {@code screen}, the panels where they are: true if the handler
		 * takes it (else a click puts the box down, as by default).
		 */
		boolean take(AbstractContainerScreen<?> screen, List<GridPanel> panels);

		/** Each frame, the panels where they're drawn: true if the handler draws the panels' items itself. */
		boolean frame(AbstractContainerScreen<?> screen, List<GridPanel> panels);

		/** What box {@code box}'s panel shows, or null for what it held as last seen. */
		@Nullable List<ItemStack> contents(int box);

		/** Boxes out of the chest for the handler, by the chest slot they came from: they keep their panels. */
		Map<Integer, ItemStack> boxes();

		/**
		 * The box last used (by the chest slot it came from), whose panel keeps the bright outline (the rest go
		 * duller), or -1.
		 */
		default int lit() {
			return -1;
		}

		/** Whether the handler still needs the chest after its screens close (the grid waits). */
		boolean busy();

		/** The grid is done with the chest: every screen closed, another chest opened, or Inventory Tweaks turned off. */
		void done();
	}

	/**
	 * Who handles the Box Grid's panels for a chest opened, asked once a chest's grid starts: by default (null) a click on
	 * a box's slot puts that box down and gives it a swap bar tab, as Mod + right-click does.
	 */
	public static final Hook<GridChest, GridHandler> BOX_GRID = Hook.of("myriad-essentials:box_grid", "Box Grid",
		"Who handles the Box Grid's panels for an opened chest. By default a click on one puts that box down and gives it a swap bar tab.");

	private final BoxGrid grid = new BoxGrid(this);
	private final ScrollBatch scrolled = new ScrollBatch();

	// The drag in progress: where the button went down, with what, and the slots it has handled.
	private @Nullable Slot pressed;
	private int pressButton = -1;
	private boolean pressShift, pressMod;
	private final Set<Slot> handled = Collections.newSetFromMap(new IdentityHashMap<>());

	public InventoryTweaks() {
		super(Tags.PLAYER, "Inventory Tweaks", "Opens shulker boxes from your inventory, and moves items with drags and the wheel.");
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
		grid.off();
		release();
		letGo();
		scrolled.send(true);
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Post e) {
		grid.tick();
		scrolled.tick();
	}

	/** The Box Grid, drawn after the window's slots (from AbstractContainerScreenMixin). */
	public static void drawGrid(ScreenAccess screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		if (m != null && m.inGame() && m.boxGrid.get() && !(screen instanceof CreativeModeInventoryScreen)) Modules.guard(m, () -> m.grid.draw(screen, graphics, mouseX, mouseY));
	}

	/** A mouse button went down: true if it was on the Box Grid (vanilla then does nothing). */
	public static boolean gridPress(ScreenAccess screen, double x, double y, int button) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		return m != null && m.inGame() && m.boxGrid.get() && Modules.guard(m, () -> m.grid.press(screen, x, y, button), false);
	}

	/** The wheel turned: true if the Box Grid scrolled its boxes with it. */
	public static boolean gridScroll(ScreenAccess screen, double x, double y, double amount) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		return m != null && m.inGame() && m.boxGrid.get() && Modules.guard(m, () -> m.grid.scrolled(screen, x, y, amount), false);
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
		// (Taken already by someone else's handler.)
		if (!e.isCancelled() && onSlotClick(e.slot(), e.button(), e.input())) e.cancel();
	}

	/** Whether {@code x, y} is on the Box Grid (a click there isn't outside the window). */
	public static boolean gridCovers(double x, double y) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		return m != null && m.boxGrid.get() && m.grid.covers(x, y);
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
		stand(inventory ? open ? c.openItem(this, index, OPEN_TICKS, options) : c.putDownItem(this, index, OPEN_TICKS, options)
			: c.putDownItem(this, from, slot.index, OPEN_TICKS, options));
		return true;
	}

	/** A box going down stands till you close a screen yourself (one that can't go down says why). */
	private CompletableFuture<Containers.ItemSession> stand(CompletableFuture<Containers.ItemSession> down) {
		return down.whenComplete((session, t) -> {
			if (t != null) {
				Why why = Why.of(t);
				if (!why.is(Why.CANCELLED) && !why.is(Why.OWNER_OFF) && !why.is(Why.LEFT_WORLD)) warn(capitalized(why.detail().isEmpty() ? why.toString() : why.detail()));
				return;
			}
			standing.add(session);
			session.finishWhenClosed();
		});
	}

	/**
	 * A click on a box's Box Grid panel, by default: the box goes down from the chest's slot {@code slot}, with a swap
	 * bar tab.
	 */
	void putDownFromGrid(Containers.View chest, int slot) {
		if (Myriad.jobs().isBusy(this) || !mc.player.containerMenu.getCarried().isEmpty()) return;
		stand(Myriad.containers().putDownItem(this, chest, slot, OPEN_TICKS, Placement.Options.forServer().withAirPlace(airPlace.get())));
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
	static boolean storage(AbstractContainerMenu menu) {
		return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
	}

	private static String capitalized(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// The mouse, from AbstractContainerScreenMixin: each through Modules.guard, so a failure turns the module off
	// rather than crashing the screen.

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
		});
	}

	/** The mouse moved with a button down, over {@code slot}: true if this took the drag (vanilla then does nothing). */
	public static boolean drag(ScreenAccess screen, @Nullable Slot slot, boolean shift) {
		InventoryTweaks m = active(screen);
		return m != null && slot != null && m.pressButton == InputConstants.MOUSE_BUTTON_LEFT && Modules.guard(m, () -> m.onDrag(screen, slot, shift), false);
	}

	/** The button came up: the drag is over. */
	public static void released(ScreenAccess screen) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		if (m != null) Modules.guard(m, m::release);
	}

	/**
	 * A mouse button went down over {@code slot}: true if it was a middle-click this sorted or balanced a crafting grid with
	 * (vanilla then does nothing).
	 */
	public static boolean middleClick(ScreenAccess screen, @Nullable Slot slot, int button) {
		InventoryTweaks m = active(screen);
		if (m == null || slot == null || button != InputConstants.MOUSE_BUTTON_MIDDLE) return false;
		if (slot.container instanceof CraftingContainer) return m.middleBalance.get() && Modules.guard(m, () -> m.balance(screen, slot), false);
		return m.middleSort.get() && Modules.guard(m, () -> m.sort(screen, slot), false);
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
		boolean storage = storage(menu);
		if (!yours && !storage) return false;
		// Your hotbar stays as you keep it; only the main inventory is sorted.
		if (yours && slot.getContainerSlot() < 9 || yours && slot.getContainerSlot() >= 36) return false;
		int[] slots = yours ? Slots.playerSlots(menu, false) : Slots.containerSlots(menu);
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
			if (t != null && !(t.getMessage() != null && t.getMessage().startsWith("You clicked")) && Slots.isOpen(menu)) warn("Stopped sorting: " + t.getMessage());
		});
		return true;
	}

	/**
	 * Shares each item in the crafting grid {@code slot} is in out evenly among the grid's slots holding it, the first
	 * slots getting one more where it doesn't divide. False, leaving the click to vanilla, with something on the cursor
	 * or in creative.
	 */
	private boolean balance(ScreenAccess screen, Slot slot) {
		AbstractContainerMenu menu = screen.essentials$menu();
		if (!menu.getCarried().isEmpty() || mc.player.isCreative()) return false;
		List<Slot> grid = menu.slots.stream().filter(s -> s.container == slot.container).toList();
		List<ItemStack> kinds = new ArrayList<>();
		for (Slot s : grid) {
			if (s.hasItem() && kinds.stream().noneMatch(k -> ItemStack.isSameItemSameComponents(k, s.getItem()))) kinds.add(s.getItem().copyWithCount(1));
		}
		for (ItemStack kind : kinds) {
			balance(screen, menu, grid.stream().filter(s -> ItemStack.isSameItemSameComponents(s.getItem(), kind)).toList());
			if (!menu.getCarried().isEmpty()) break;
		}
		return true;
	}

	/**
	 * Evens out {@code slots} (all the same item) as you would by hand: the stack furthest over its share is picked up
	 * whole, and its share and the rest spread back with vanilla's drags over the slots short of theirs, until none is
	 * over. A click that doesn't come out as planned stops it, what's on the cursor going back where it came from.
	 */
	private void balance(ScreenAccess screen, AbstractContainerMenu menu, List<Slot> slots) {
		int n = slots.size(), total = 0;
		for (Slot s : slots) total += s.getItem().getCount();
		int[] goal = new int[n];
		for (int i = 0; i < n; i++) goal[i] = total / n + (i < total % n ? 1 : 0);
		for (int round = 0; round < n; round++) {
			int from = -1;
			for (int i = 0; i < n; i++) {
				int over = slots.get(i).getItem().getCount() - goal[i];
				if (over > 0 && (from < 0 || over > slots.get(from).getItem().getCount() - goal[from])) from = i;
			}
			if (from < 0) return;
			Slot source = slots.get(from);
			int carried = source.getItem().getCount();
			screen.essentials$click(source, 0, ContainerInput.PICKUP);
			if (menu.getCarried().getCount() != carried) {
				if (!menu.getCarried().isEmpty()) screen.essentials$click(source, 0, ContainerInput.PICKUP);
				return;
			}
			// Its share back, the rest to the slots short of theirs (which together are short of at least as many).
			int[] want = new int[n];
			want[from] = goal[from];
			int left = carried - goal[from];
			for (int i = 0; i < n && left > 0; i++) {
				if (i == from) continue;
				want[i] = Math.min(left, Math.max(0, goal[i] - slots.get(i).getItem().getCount()));
				left -= want[i];
			}
			spread(screen, menu, slots, want);
			if (!menu.getCarried().isEmpty()) {
				screen.essentials$click(source, 0, ContainerInput.PICKUP);
				return;
			}
		}
	}

	/**
	 * Puts {@code want[i]} of the carried stack into each of {@code slots} (adding up to all of it), with vanilla's drags:
	 * an even split while every slot still wanting some takes that many, else one each. A drag over one slot is that
	 * slot's click, as vanilla makes it.
	 */
	private void spread(ScreenAccess screen, AbstractContainerMenu menu, List<Slot> slots, int[] want) {
		while (true) {
			List<Slot> to = new ArrayList<>();
			int least = Integer.MAX_VALUE;
			for (int i = 0; i < slots.size(); i++) {
				if (want[i] <= 0) continue;
				to.add(slots.get(i));
				least = Math.min(least, want[i]);
			}
			int carried = menu.getCarried().getCount();
			if (to.isEmpty() || carried < to.size()) return;
			boolean even = carried / to.size() <= least;
			int each = even ? carried / to.size() : 1, type = even ? 0 : 1;
			if (to.size() == 1) {
				screen.essentials$click(to.getFirst(), type, ContainerInput.PICKUP);
			} else {
				screen.essentials$clickNoSlot(AbstractContainerMenu.getQuickcraftMask(0, type), ContainerInput.QUICK_CRAFT);
				for (Slot s : to) screen.essentials$click(s, AbstractContainerMenu.getQuickcraftMask(1, type), ContainerInput.QUICK_CRAFT);
				screen.essentials$clickNoSlot(AbstractContainerMenu.getQuickcraftMask(2, type), ContainerInput.QUICK_CRAFT);
			}
			for (int i = 0; i < slots.size(); i++) if (want[i] > 0) want[i] -= each;
			if (menu.getCarried().getCount() != carried - each * to.size()) return;
		}
	}

	private void release() {
		pressed = null;
		pressButton = -1;
		handled.clear();
	}

	/** Shift-drag: vanilla shift-clicked the first slot on the press; each slot after it is shift-clicked once. */
	private boolean onDrag(ScreenAccess screen, Slot slot, boolean shift) {
		if (!screen.essentials$menu().getCarried().isEmpty() || !shiftDrag.get() || !pressShift || !shift) return false;
		if (pressed != null) handled.add(pressed);
		if (!slot.hasItem() || !slot.mayPickup(mc.player) || !handled.add(slot)) return true;
		if (!canClick()) {
			handled.remove(slot);
			return true;
		}
		screen.essentials$click(slot, 0, ContainerInput.QUICK_MOVE);
		return true;
	}

	/**
	 * One item from {@code slot} to the other inventory ({@code away}), or one from there into {@code slot}: shown at
	 * once, and moved with the rest once the wheel rests (see {@link ScrollBatch}).
	 */
	private boolean onScroll(ScreenAccess screen, Slot slot, boolean away) {
		AbstractContainerMenu menu = screen.essentials$menu();
		if (!menu.getCarried().isEmpty()) return false;
		ItemStack item = shown(menu, slot);
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
		scrolled.add(screen, from, to, shown(menu, from));
		return true;
	}

	/** What {@code slot} shows: what it holds, with Scroll Move's items on their way in and out. */
	private ItemStack shown(AbstractContainerMenu menu, Slot slot) {
		return scrolled.shown(menu, slot, slot.getItem());
	}

	/** What {@code slot} of {@code menu} shows (from AbstractContainerScreenMixin, for the slot and its tooltip). */
	public static ItemStack shownIn(ScreenAccess screen, Slot slot, ItemStack real) {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		return m == null || !m.scrolled.pending() ? real : m.scrolled.shown(screen.essentials$menu(), slot, real);
	}

	/** You're about to click, press a key or close the screen: Scroll Move's waiting items go first. */
	public static void beforeInput() {
		InventoryTweaks m = Modules.active(InventoryTweaks.class);
		if (m != null && m.scrolled.pending()) Modules.guard(m, () -> m.scrolled.send(true));
	}

	/** Where one of {@code item} from {@code slot} goes on the other side: a stack of it with room, else an empty slot. */
	private @Nullable Slot target(AbstractContainerMenu menu, Slot slot, ItemStack item) {
		Slot empty = null;
		for (Slot s : menu.slots) {
			if (!otherSide(menu, slot, s) || !s.mayPlace(item)) continue;
			ItemStack there = shown(menu, s);
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
			ItemStack there = shown(menu, s);
			// Only slots you could put it back in: taking from a crafting or furnace output would craft or take the product.
			if (otherSide(menu, slot, s) && !there.isEmpty() && ItemStack.isSameItemSameComponents(there, item) && s.mayPickup(mc.player) && s.mayPlace(item)) return s;
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
		// Your inventory and the container's own slots, before yours: not slots after them (another mod's), which go with
		// your inventory only.
		if (mine && !theirs && other.index > slot.index) return false;
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
