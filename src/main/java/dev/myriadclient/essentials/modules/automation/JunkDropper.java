package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ContainerScreenEvent;
import dev.myriadclient.api.event.events.ItemTooltipEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.StringListSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Throws out what you don't want as soon as it's in your inventory: the junk you pick up flying a highway. Items are
 * picked from the item list, every one of them whatever it's called. Each entry of Named names text an item's name has
 * ({@code "discord"}, any item), an item with that text ({@code golden_sword "Free Kit"}), or one map
 * ({@code filled_map #1234}, copies of it, other maps kept).
 * <p>
 * With Add Bind held in an inventory screen, a left click on an item adds every one of it to Items and a right click just
 * that one to Named (that map, or that item by that name); a click on something already junk takes off what made it junk.
 * The click does nothing else then.
 * <p>
 * Throws every junk stack at once (one click each), from the hotbar and main inventory (not your armour or off hand),
 * and only while no container is open and the server takes clicks (not while you sprint or move on Grim), so it never
 * costs you speed.
 */
public class JunkDropper extends Module {
	private final RegistryListSetting<Item> items = sgGeneral.items("Items").description("Items to throw out, every one whatever it's called.")
		.filter(i -> i != Items.AIR).build();
	private final StringListSetting named = sgGeneral.stringList("Named").description("Junk by name: text in its name (\"discord\"), an item "
		+ "with it (golden_sword \"Free Kit\"), or one map (filled_map #1234).").onChanged(v -> rules = null).build();
	private final KeybindSetting addBind = sgGeneral.keybind("Add Bind").description("Hold in your inventory and click an item: left click for "
		+ "every one of it, right click for just that one (that map, or that name). Click junk to take it off.").build();
	/** Junk as one typed list, before 2.2.0: read once into Items and Named, then left empty. */
	private final StringListSetting oldJunk = sgGeneral.stringList("Junk").visible(() -> false).onChanged(v -> moveOldJunk()).build();

	/** An entry: {@code item}, {@code "name text"}, {@code item "name text"}, {@code filled_map #id}. */
	private static final Pattern ENTRY = Pattern.compile("^\\s*([a-z0-9_.\\-]+(?::[a-z0-9_./\\-]+)?)?\\s*(?:\"([^\"]*)\")?\\s*(?:#(\\d+))?\\s*$");

	private record Rule(String entry, @Nullable Item item, @Nullable String name, int map) {
		boolean matches(ItemStack stack) {
			if (item != null && !stack.is(item)) return false;
			if (map >= 0) {
				MapId id = stack.get(DataComponents.MAP_ID);
				if (id == null || id.id() != map) return false;
			}
			return name == null || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(name);
		}
	}

	/** Named's entries, read once after each change; ones that don't read as an entry are left out. */
	private @Nullable List<Rule> rules;

	public JunkDropper() {
		super(Tags.AUTOMATION, "Junk Dropper", "Throws out items you've listed as junk as soon as you pick them up.");
	}

	/** Puts what the old Junk list held into Items (a bare item) or Named (the rest). */
	private void moveOldJunk() {
		if (oldJunk.get().isEmpty()) return;
		List<String> entries = List.copyOf(oldJunk.get());
		oldJunk.set(new ArrayList<>());
		for (String entry : entries) {
			Rule r = parse(entry);
			if (r == null) continue;
			if (r.item() != null && r.name() == null && r.map() < 0) items.add(r.item());
			else named.add(entry);
		}
	}

	private List<Rule> rules() {
		if (rules == null) {
			List<Rule> read = new ArrayList<>();
			for (String entry : named.get()) {
				Rule r = parse(entry);
				if (r != null) read.add(r);
			}
			rules = read;
		}
		return rules;
	}

	private static @Nullable Rule parse(String entry) {
		Matcher m = ENTRY.matcher(entry.toLowerCase(Locale.ROOT));
		if (!m.matches() || m.group(1) == null && m.group(2) == null) return null;
		Item item = null;
		if (m.group(1) != null) {
			Identifier id = Identifier.tryParse(m.group(1));
			if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
			item = BuiltInRegistries.ITEM.getValue(id);
		}
		String name = m.group(2) == null || m.group(2).isBlank() ? null : m.group(2);
		int map = m.group(3) == null ? -1 : Integer.parseInt(m.group(3));
		return new Rule(entry, item, name, map);
	}

	/** What makes {@code stack} junk: its item, or the Named entry it matches; null if it isn't. */
	private @Nullable String junkReason(ItemStack stack) {
		if (stack.isEmpty()) return null;
		if (items.contains(stack.getItem())) return any(stack);
		for (Rule r : rules()) if (r.matches(stack)) return r.entry();
		return null;
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame() || mc.player.isCreative() || items.get().isEmpty() && rules().isEmpty() || addBind.get().isPressed()) return;
		// Your own inventory's clicks, which go without its screen up; a container's screen has its own.
		if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return;
		if (!Myriad.inventory().safeToClick()) return;
		var inventory = mc.player.getInventory();
		// Every junk stack in this tick (a whole stack is one click), as far as the click budget goes; the rest the next.
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			if (junkReason(stack) == null || Myriad.inventory().isSpared(stack)) continue;
			// (One not dropped, its slot's last click unanswered or the budget spent, waits for the next tick.)
			Myriad.inventory().drop(i, true);
		}
	}

	/** With Add Bind held, a click on an item in your inventory adds it to Items or Named, or takes it off. */
	@Subscribe
	private void onClick(ContainerScreenEvent.Click e) {
		Slot slot = e.slot();
		if (!inGame() || !addBind.get().isPressed() || slot == null || !slot.hasItem() || e.input() != ContainerInput.PICKUP) return;
		if (slot.container != mc.player.getInventory() || !e.screen().getMenu().getCarried().isEmpty()) return;
		e.cancel();
		ItemStack stack = slot.getItem();
		List<String> off = new ArrayList<>();
		if (items.contains(stack.getItem())) {
			items.remove(stack.getItem());
			off.add(any(stack));
		}
		for (Rule r : rules()) if (r.matches(stack)) off.add(r.entry());
		if (!off.isEmpty()) {
			off.forEach(named::remove);
			info("No longer junk: " + String.join(", ", off));
			return;
		}
		String entry = e.button() == 1 ? exactly(stack) : any(stack);
		if (entry.equals(any(stack))) items.add(stack.getItem());
		else named.add(entry);
		info("Junk: " + entry);
	}

	/** Every one of the item. */
	private static String any(ItemStack stack) {
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}

	/** Just this one: its map, or the item by the name it has; one with neither is every one of it. */
	private static String exactly(ItemStack stack) {
		MapId map = stack.get(DataComponents.MAP_ID);
		if (map != null) return any(stack) + " #" + map.id();
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		if (name != null && !name.getString().isBlank() && !name.getString().contains("\"")) return any(stack) + " \"" + name.getString() + "\"";
		return any(stack);
	}

	/** With Add Bind held, an item's tooltip says what a click does. */
	@Subscribe
	private void onTooltip(ItemTooltipEvent e) {
		if (!mc.isSameThread() || !inGame() || e.lines().isEmpty() || e.stack().isEmpty()) return;
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>) || !addBind.get().isPressed()) return;
		String reason = junkReason(e.stack());
		if (reason != null) {
			e.lines().add(Component.literal("Junk (" + reason + "): click to keep").withStyle(ChatFormatting.GRAY));
			return;
		}
		String exactly = exactly(e.stack()), any = any(e.stack());
		e.lines().add(Component.literal("Left click: junk all " + any).withStyle(ChatFormatting.GRAY));
		if (!exactly.equals(any)) e.lines().add(Component.literal("Right click: junk " + exactly).withStyle(ChatFormatting.GRAY));
	}
}
