package dev.myriadclient.grimtest;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/**
 * Answers inventory clicks as 2b2t does, where a vanilla server sends nothing for a click that came out as the client
 * predicted, so what that does to a client never shows here otherwise. {@code answerclicks on|pre|slots|off} (console or RCON;
 * off when the server starts):
 * <ul>
 * <li>{@code on}, like 2b2t: each click moves the menu's state id on, so the client's next clicks of a burst carry an
 * old one and the server sends the whole menu back after each of them (as it stands after that click), and the menu
 * is sent once more the tick after.</li>
 * <li>{@code pre}, harsher: the whole menu as it was before the click, sent as the click arrives, so the client always
 * sees each click undone for a moment, a round trip late.</li>
 * <li>{@code slots}, 2b2t's own packets as captured from it (its version translation, which can't check the hashed
 * stacks a 1.21.5+ client sends): every click, a drag's start and middle too, answered as the server takes it with
 * the slots the client said it changed, the crafting result (your inventory), then the cursor when the client said
 * something's on it, or else those slots again. So a drag's start and middle answer "the stack's still on the cursor"
 * after its end already put it down.</li>
 * </ul>
 */
public final class AnswerClicks extends JavaPlugin implements Listener {
	private static final String HANDLER = "myriad_answer_clicks";
	private static final Set<String> MODES = Set.of("on", "pre", "slots", "off");
	private volatile String mode = "off";

	@Override
	public void onEnable() {
		getServer().getPluginManager().registerEvents(this, this);
		for (Player p : getServer().getOnlinePlayers()) watch(p);
	}

	@EventHandler
	private void onJoin(PlayerJoinEvent e) {
		watch(e.getPlayer());
	}

	/** Sees the player's clicks as they arrive, before the server takes them (for {@code slots}). */
	private void watch(Player p) {
		ServerPlayer player = ((CraftPlayer) p).getHandle();
		var pipeline = player.connection.connection.channel.pipeline();
		if (pipeline.get(HANDLER) != null || pipeline.get("packet_handler") == null) return;
		pipeline.addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {
			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
				super.channelRead(ctx, msg);
				// The click was just queued for the server thread; this answer goes in right behind it, before the next.
				if (mode.equals("slots") && msg instanceof ServerboundContainerClickPacket click) MinecraftServer.getServer().execute(() -> answer(player, click));
			}
		});
	}

	private static void answer(ServerPlayer player, ServerboundContainerClickPacket click) {
		AbstractContainerMenu menu = player.containerMenu;
		if (menu.containerId != click.getContainerId() || player.hasDisconnected()) return;
		var slots = click.getChangedSlots().keySet().intStream().filter(i -> i >= 0 && i < menu.slots.size()).toArray();
		for (int i : slots) send(player, menu, i);
		if (menu instanceof InventoryMenu) send(player, menu, InventoryMenu.RESULT_SLOT);
		if (!click.getCarriedItem().isEmpty()) player.connection.send(new ClientboundSetCursorItemPacket(menu.getCarried().copy()));
		else for (int i : slots) send(player, menu, i);
	}

	private static void send(ServerPlayer player, AbstractContainerMenu menu, int slot) {
		player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), slot, menu.getSlot(slot).getItem().copy()));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 1 && MODES.contains(args[0])) mode = args[0];
		sender.sendMessage("Answering clicks: " + mode);
		return true;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	private void onClick(InventoryClickEvent e) {
		if (!(e.getWhoClicked() instanceof Player p)) return;
		switch (mode) {
			case "pre" -> p.updateInventory();
			case "on" -> {
				nextStateId(p);
				getServer().getScheduler().runTask(this, p::updateInventory);
			}
			default -> {
			}
		}
	}

	/** Moves the open menu's state id on without telling the client (the server's AbstractContainerMenu.incrementStateId). */
	private void nextStateId(Player p) {
		try {
			Object handle = p.getClass().getMethod("getHandle").invoke(p);
			Object menu = handle.getClass().getField("containerMenu").get(handle);
			menu.getClass().getMethod("incrementStateId").invoke(menu);
		} catch (ReflectiveOperationException ex) {
			getLogger().warning("couldn't move the state id on: " + ex);
		}
	}
}
