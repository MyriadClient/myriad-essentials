package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.StringSetting;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Renames items at an anvil: every one of the listed items in your inventory gets the name, as soon as there's an anvil
 * in reach and no screen up. The anvil is opened without its screen, each stack goes in, comes out renamed and goes back
 * to the slot it came from, so your inventory keeps its layout; then the anvil closes. Each stack costs a level (more
 * for one already worked on at an anvil; one too expensive is skipped), and an anvil wears as it's used, as by hand.
 */
public class AutoRename extends Module {
	/** Stacks the anvil wouldn't rename (too expensive), left alone until the settings change. */
	private final List<ItemStack> skipped = new ArrayList<>();
	private final RegistryListSetting<Item> items = sgGeneral.items("Items").description("Items to rename, every one of them.")
		.filter(i -> i != Items.AIR).onChanged(v -> skipped.clear()).build();
	private final StringSetting name = sgGeneral.string("Name").description("The name they get (up to 50 characters).")
		.onChanged(v -> skipped.clear()).build();
	private final BoolSetting keepSlots = sgGeneral.bool("Keep Slots").defaultValue(true)
		.description("Each renamed stack goes back to its own slot (three clicks a stack). Off, it goes to the first free one: two clicks, half again as fast.").build();

	/** Ticks to wait after the anvil couldn't be opened or the work stopped short. */
	private static final int RETRY_TICKS = 60;
	/** Ticks the anvil may take to open. */
	private static final int OPEN_TICKS = 20;

	/** The anvil open for renaming: its screen hidden; null when none is. */
	private Containers.@Nullable View anvil;
	/** An anvil being opened: its screen is hidden as it comes. */
	private @Nullable BlockPos opening;
	/** Completes once the anvil's work ends (true if nothing's left to rename). */
	private @Nullable CompletableFuture<Boolean> finished;
	private int wait;
	private boolean busy, warnedLevels;

	public AutoRename() {
		super(Tags.AUTOMATION, "Auto Rename", "Renames items at an anvil in reach.");
	}

	@Override
	protected void onEnable() {
		skipped.clear();
		wait = 0;
		warnedLevels = false;
	}

	@Override
	protected void onDisable() {
		endAnvil(false);
		opening = null;
		busy = false;
	}

	/** The name to give, trimmed; empty for none. */
	private String newName() {
		String n = name.get().strip();
		return n.length() > AnvilMenu.MAX_NAME_LENGTH ? n.substring(0, AnvilMenu.MAX_NAME_LENGTH) : n;
	}

	/** Whether {@code stack} is one to rename: a listed item without the name, not skipped. */
	private boolean needs(ItemStack stack) {
		if (stack.isEmpty() || !items.contains(stack.getItem())) return false;
		String n = newName();
		if (n.isEmpty() || stack.getHoverName().getString().equals(n)) return false;
		for (ItemStack s : skipped) if (ItemStack.isSameItemSameComponents(s, stack)) return false;
		return true;
	}

	/** Whether {@code stack} is a listed item with the name, as the anvil's result should be. */
	private boolean renamed(ItemStack stack) {
		return !stack.isEmpty() && items.contains(stack.getItem()) && stack.getHoverName().getString().equals(newName());
	}

	private boolean affordable() {
		if (mc.player.hasInfiniteMaterials() || mc.player.experienceLevel > 0) {
			warnedLevels = false;
			return true;
		}
		if (!warnedLevels) warn("Renaming needs a level of experience per stack");
		warnedLevels = true;
		return false;
	}

	private int inventoryIndexToRename() {
		var inventory = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (needs(inventory.getItem(i))) return i;
		return -1;
	}

	/** The nearest anvil in reach, or null. */
	private @Nullable BlockPos anvilInReach() {
		double range = Reach.blockRange();
		Vec3 eyes = Reach.eyes();
		BlockPos center = BlockPos.containing(eyes);
		int r = (int) Math.ceil(range);
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (!mc.level.getBlockState(p).is(BlockTags.ANVIL) || !Reach.canReach(p)) continue;
			double d = Vec3.atCenterOf(p).distanceToSqr(eyes);
			if (d < bestDist) {
				bestDist = d;
				best = p.immutable();
			}
		}
		return best;
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (anvil != null) {
			renameStep();
			return;
		}
		if (busy || wait > 0 && wait-- > 0) return;
		// Your own inventory, with nothing else open: renamed at the anvil, if there's one.
		if (mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu || Myriad.containers().current().isPresent()) return;
		if (!mc.player.containerMenu.getCarried().isEmpty() || inventoryIndexToRename() < 0 || !affordable()) return;
		if (!Myriad.inventory().safeToClick()) return;
		BlockPos at = anvilInReach();
		if (at == null) {
			wait = 10;
			return;
		}
		busy = true;
		openAnvil(at).whenComplete((ok, t) -> {
			busy = false;
			if (t != null || !ok) wait = RETRY_TICKS;
		});
	}

	/** Opens the anvil at {@code pos} without its screen and renames everything to rename; completes once it's closed. */
	private CompletableFuture<Boolean> openAnvil(BlockPos pos) {
		opening = pos;
		CompletableFuture<Boolean> done = new CompletableFuture<>();
		Myriad.containers().open(this, pos, OPEN_TICKS, Placement.Options.forServer()).whenComplete((view, t) -> {
			opening = null;
			if (t != null || !isEnabled() || !(view.handler() instanceof AnvilMenu menu)) {
				if (view != null) view.close();
				done.complete(false);
				return;
			}
			// The name holds for every stack put in while it's open; the client works the result out as the server does.
			String n = newName();
			menu.setItemName(n);
			mc.getConnection().send(new ServerboundRenameItemPacket(n));
			anvil = view;
			finished = done;
		});
		return done;
	}

	/**
	 * One stack a tick: shift-clicked into the anvil, the renamed result picked up and put down where it came from (three
	 * clicks, which the server takes in order, so the result is there when it's picked up), or shift-clicked out to the
	 * first free slot without Keep Slots (two). Clicks are what limit it: core keeps them under what 2b2t allows (it
	 * disconnects at about 80 in 4 seconds), so a long list goes in bursts.
	 */
	private void renameStep() {
		Containers.View view = anvil;
		if (!view.isOpen() || !(view.handler() instanceof AnvilMenu menu) || mc.player.containerMenu != menu) {
			// The anvil broke, or something else opened.
			endAnvil(false);
			return;
		}
		if (!menu.getCarried().isEmpty()) return;
		// Something left in the anvil (put in by hand, or a click the server refused): back where there's room.
		for (int s = 0; s < AnvilMenu.RESULT_SLOT; s++) {
			if (menu.getSlot(s).hasItem()) {
				if (Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1) && Myriad.inventory().prepareClick()) view.quickMove(s);
				return;
			}
		}
		int i = inventoryIndexToRename();
		if (i < 0 || !affordable()) {
			endAnvil(true);
			return;
		}
		int clicks = keepSlots.get() ? 3 : 2;
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, clicks) || !Myriad.inventory().prepareClick()) return;
		int slot = view.playerSlot(i);
		ItemStack before = mc.player.getInventory().getItem(i).copy();
		view.click(slot, 0, ContainerInput.QUICK_MOVE);
		ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
		boolean levels = mc.player.hasInfiniteMaterials() || mc.player.experienceLevel >= menu.getCost();
		boolean in = menu.getSlot(AnvilMenu.INPUT_SLOT).hasItem();
		if (!in || result.isEmpty() || !renamed(result) || menu.getCost() <= 0 || !levels) {
			// Too expensive (or it didn't go in): back where it was, and left alone from now on.
			skipped.add(before);
			if (in) {
				view.click(AnvilMenu.INPUT_SLOT, 0, ContainerInput.PICKUP);
				view.click(slot, 0, ContainerInput.PICKUP);
			}
			if (in && menu.getCost() > 0) warn(before.getHoverName().getString() + " needs " + menu.getCost() + " levels to rename: skipped");
			return;
		}
		if (keepSlots.get()) {
			view.click(AnvilMenu.RESULT_SLOT, 0, ContainerInput.PICKUP);
			view.click(slot, 0, ContainerInput.PICKUP);
		} else {
			view.quickMove(AnvilMenu.RESULT_SLOT);
		}
	}

	private void endAnvil(boolean ok) {
		Containers.View view = anvil;
		anvil = null;
		if (view != null && view.isOpen()) view.close();
		CompletableFuture<Boolean> done = finished;
		finished = null;
		if (done != null) done.complete(ok);
	}

	/** The anvil's screen stays hidden: its name box would send names of its own. */
	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (e.screen() instanceof AnvilScreen s && (opening != null || anvil != null) && mc.player != null && s.getMenu() == mc.player.containerMenu) {
			e.setScreen(null);
		}
	}
}
