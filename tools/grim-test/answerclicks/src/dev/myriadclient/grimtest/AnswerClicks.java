package dev.myriadclient.grimtest;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Answers inventory clicks as 2b2t does, where a vanilla server sends nothing for a click that came out as the client
 * predicted, so what that does to a client never shows here otherwise. {@code answerclicks on|pre|off} (console or RCON;
 * off when the server starts):
 * <ul>
 * <li>{@code on}, like 2b2t: each click moves the menu's state id on, so the client's next clicks of a burst carry an
 * old one and the server sends the whole menu back after each of them (as it stands after that click), and the menu
 * is sent once more the tick after.</li>
 * <li>{@code pre}, harsher: the whole menu as it was before the click, sent as the click arrives, so the client always
 * sees each click undone for a moment, a round trip late.</li>
 * </ul>
 */
public final class AnswerClicks extends JavaPlugin implements Listener {
	private String mode = "off";

	@Override
	public void onEnable() {
		getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 1 && (args[0].equals("on") || args[0].equals("pre") || args[0].equals("off"))) mode = args[0];
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
