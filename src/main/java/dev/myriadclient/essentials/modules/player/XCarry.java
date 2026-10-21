package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

/**
 * Keeps items in your 2x2 crafting grid after closing your inventory, by never telling the server it was closed.
 * Containers still close normally. Turning the module off closes the inventory for real, which drops the items
 * back into your inventory.
 */
public class XCarry extends Module {
	public XCarry() {
		super(Tags.PLAYER, "X Carry", "Use the crafting grid as four extra inventory slots.");
	}

	@Subscribe(packets = ServerboundContainerClosePacket.class)
	private void onSend(PacketEvent.Send e) {
		if (mc.player != null && ((ServerboundContainerClosePacket) e.packet()).getContainerId() == mc.player.inventoryMenu.containerId) e.cancel();
	}

	@Override
	protected void onDisable() {
		if (inGame()) mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
	}
}
