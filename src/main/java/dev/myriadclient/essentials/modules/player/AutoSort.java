package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.essentials.util.Layouts;
import dev.myriadclient.essentials.util.Menus;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Sorts chests, barrels and shulker boxes as you open them: stacks of the same item merged, everything in order from
 * the first slot. Each click puts a stack where it belongs (swapping through the cursor), so a full double chest takes
 * a few seconds at the server's click budget. Clicking in the container yourself stops it.
 */
public class AutoSort extends Module {
	private final EnumSetting<Layouts.Order> order = sgGeneral.oneOf("Order", Layouts.Order.ITEM)
		.description("Item: the game's own order, close to the creative tabs. Name: A to Z. Count: what you have most of first.").build();
	private final BoolSetting shulkers = sgGeneral.bool("Shulker Boxes").description("Sort shulker boxes too, not only chests and barrels.")
		.defaultValue(true).build();

	private AbstractContainerMenu sorting;

	public AutoSort() {
		super(Tags.PLAYER, "Auto Sort", "Sorts containers as you open them.");
	}

	@Override
	protected void onDisable() {
		sorting = null;
	}

	/** Whether a container is being sorted. */
	public boolean busy() {
		return sorting != null;
	}

	@Subscribe
	private void onLoaded(ContainerEvent.Loaded e) {
		AbstractContainerMenu menu = e.view().handler();
		// A module opened it to work in: not yours to sort.
		if (e.view().opener() != null) return;
		if (!(menu instanceof ChestMenu) && !(shulkers.get() && menu instanceof ShulkerBoxMenu)) return;
		sort(e.view());
	}

	/** Sorts the container's own slots; the player's empty slots take what has to be moved out of the way. */
	public void sort(Containers.View view) {
		AbstractContainerMenu menu = view.handler();
		if (sorting == menu) return;
		int[] slots = Menus.containerSlots(menu);
		List<ItemStack> layout = Layouts.sorted(menu, slots, order.get());
		if (Layouts.holds(menu, slots, layout)) return;
		sorting = menu;
		view.arrange(Containers.Arrangement.of(slots, layout).dumpTo(Menus.playerSlots(menu, true))).whenComplete((ok, t) -> {
			if (sorting == menu) sorting = null;
			if (t != null) {
				if (Menus.isOpen(menu)) warn("Stopped sorting: " + t.getMessage());
			} else if (!ok) {
				warn("Couldn't finish sorting (the server kept something else)");
			}
		});
	}
}
