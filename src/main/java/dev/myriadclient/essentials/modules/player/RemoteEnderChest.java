package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.Containers;
import net.minecraft.world.level.block.Blocks;

/**
 * Keeps an ender chest open on the server after you close it (core's Containers.hold): opening your inventory shows it
 * again at once, with no packets, from as far as the server still allows (a vanilla server closes it once you're out of
 * reach; 2b2t leaves it open). It shows your inventory too, and your inventory's clicks, yours and other modules', go
 * through it meanwhile. Opening any other container lets it go, as the server closes it then. An ender chest Inventory
 * Tweaks put down for you still goes back when you close it: keeping it open needs it standing.
 */
public class RemoteEnderChest extends Module {
	public RemoteEnderChest() {
		super(Tags.PLAYER, "Remote Ender Chest", "Keep an ender chest open after you close it, and open it again with your inventory key.");
	}

	@Override
	protected void onDisable() {
		Myriad.containers().held().filter(this::enderChest).ifPresent(v -> Myriad.containers().release());
	}

	@Subscribe
	private void onOpened(ContainerEvent.Opened e) {
		// One Inventory Tweaks put down isn't held (core refuses it): it goes back when you close it.
		if (inGame() && enderChest(e.view())) Myriad.containers().hold(e.view());
	}

	private boolean enderChest(Containers.View view) {
		return view.pos() != null && mc.level != null && mc.level.getBlockState(view.pos()).is(Blocks.ENDER_CHEST);
	}
}
