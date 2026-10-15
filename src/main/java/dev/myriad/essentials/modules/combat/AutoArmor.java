package dev.myriad.essentials.modules.combat;

import dev.myriad.api.Myriad;
import dev.myriad.essentials.modules.movement.ElytraFlight;
import dev.myriad.essentials.modules.movement.NoDurability;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.InteractEvent;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.KeyEvent;
import dev.myriad.api.event.events.MouseButtonEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.KeybindSetting;
import dev.myriad.api.setting.SettingGroup;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.util.Slots;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Wears the best armour you're carrying, one piece at a time. Best means most armour and toughness, then your
 * preferred protection enchantment; curse of binding is skipped. A piece isn't swapped for a copy that's only less worn:
 * it's used until it's worn down to Preserve, then the freshest other goes on. Pieces worn down to Preserve are put aside to be
 * mended: swapped for another, or taken off when there's none (an elytra once you've landed), and other modules pass
 * them over too.
 * <p>
 * For the chest slot you choose between a chestplate and an elytra, and the Chest Swap bind flips between them (and
 * swaps straight away). Until you land, an elytra you're flying with is never taken off. No Durability Elytra keeps an
 * elytra you fly yourself (rockets, Rocket Boost) from wearing out (see {@link NoDurability}); Recast and Altitude
 * do their own swapping, so it stands down while one of them is on.
 */
public class AutoArmor extends Module implements NoDurability.Owner {
	public enum Protection {
		/** Protection, but Blast Protection on leggings: the usual PvP kit. */
		BLAST_LEGS, PROTECTION, BLAST, FIRE, PROJECTILE
	}

	public enum Chest {
		CHESTPLATE, ELYTRA
	}

	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private final IntSetting delay = sgGeneral.intSetting("Delay").description("Ticks between equipping pieces.").defaultValue(2).range(0, 20).build();
	private final EnumSetting<Protection> prefer = sgGeneral.enumSetting("Prefer", Protection.BLAST_LEGS)
		.description("Protection enchantment to favour between otherwise equal pieces. Blast Legs: Protection, with Blast Protection on leggings.").build();
	private final DoubleSetting preserve = sgGeneral.doubleSetting("Preserve").description("Put pieces with less durability than this (%) aside, to be mended.")
		.defaultValue(5).range(0, 50).decimals(0).build();

	private final SettingGroup sgChest = settings.group("Chest Swap");
	private final EnumSetting<Chest> chest = sgChest.enumSetting("Chest", Chest.CHESTPLATE).description("What to wear in the chest slot.").build();
	private final KeybindSetting swapKey = sgChest.keybind("Swap Bind").description("Flip between chestplate and elytra.").build();
	private final BoolSetting awaitLanding = sgChest.bool("Await Landing").description("Keep the elytra on until you land.").defaultValue(true).build();
	private final BoolSetting notify = sgChest.bool("Notify").description("Say which one you swapped to.").defaultValue(true).build();
	private final BoolSetting noDurabilityElytra = sgChest.bool("No Durability Elytra")
		.description("While you fly an elytra yourself, swap it with a chestplate from your inventory so the server barely wears it, and open it again if the glide drops in mid-air. Recast and Altitude do their own.")
		.build();

	/** No Durability Elytra's swapping, and whether it's running (not while Recast or Altitude flies). */
	private final NoDurability noDur = new NoDurability(this);
	private boolean noDurRunning;

	private int wait;
	/** A worn-down piece couldn't be taken off for lack of room (said once until it can). */
	private boolean warnedFull;

	public AutoArmor() {
		super(Categories.COMBAT, "Auto Armor", "Wears your best armour, and swaps between chestplate and elytra on a bind.");
	}

	@Override
	protected void onEnable() {
		warnedFull = false;
		Myriad.inventory().spare(this, this::wornDown);
	}

	@Override
	protected void onDisable() {
		stopNoDurability();
		Myriad.inventory().spare(this, null);
	}

	/** Armour or an elytra worn down to Preserve. */
	private boolean wornDown(ItemStack s) {
		return ItemInfo.armorSlot(s) != null && s.isDamageableItem() && ItemInfo.durabilityFraction(s) * 100 < preserve.get();
	}

	/** Whether No Durability Elytra swaps for you: on, with neither Recast nor Altitude flying. */
	public boolean noDurabilityElytra() {
		return isEnabled() && noDurabilityElytra.get() && !ElytraFlight.flightModuleOn();
	}

	/** Whether No Durability Elytra is swapping the elytra and chestplate right now. */
	public boolean elytraSwapping() {
		return noDurRunning && noDur.swapping();
	}

	@Override
	public boolean noDurability() {
		return true;
	}

	/** With the flight modules' own, before the tick's actions (see NoDurability.tick). */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 10)
	private void tickNoDurability(TickEvent.Pre e) {
		if (noDurabilityElytra() && inGame()) {
			if (!noDurRunning) {
				noDur.enable();
				noDurRunning = true;
			}
			noDur.tick();
		} else {
			stopNoDurability();
		}
	}

	private void stopNoDurability() {
		if (!noDurRunning) return;
		noDurRunning = false;
		noDur.disable();
	}

	@Subscribe(priority = Priority.LOWEST)
	private void noDurabilityInput(InputEvent e) {
		if (noDurRunning) noDur.onInput(e);
	}

	@Subscribe
	private void noDurabilityUse(InteractEvent.Item e) {
		if (noDurRunning) noDur.onUse(e);
	}

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void noDurabilitySetBack(PacketEvent.Receive e) {
		if (noDurRunning) noDur.setBack();
	}

	@Override
	public String hudInfo() {
		return chest.get() == Chest.ELYTRA ? "Elytra" : null;
	}

	@Subscribe
	private void onKey(KeyEvent e) {
		if (swapKey.wasPressed(e)) flipChest();
	}

	@Subscribe
	private void onMouse(MouseButtonEvent e) {
		if (swapKey.wasPressed(e)) flipChest();
	}

	private void flipChest() {
		chest.set(chest.get() == Chest.ELYTRA ? Chest.CHESTPLATE : Chest.ELYTRA);
		wait = 0;
		if (notify.get()) info("Chest: " + (chest.get() == Chest.ELYTRA ? "elytra" : "chestplate"));
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame() || mc.player.isCreative()) return;
		if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen) && mc.player.containerMenu != mc.player.inventoryMenu) return;
		if (wait > 0) {
			wait--;
			return;
		}
		for (EquipmentSlot slot : SLOTS) {
			Step step = equipBest(slot);
			if (step == Step.NONE) continue;
			// Waiting for a still tick goes on next tick, not after the delay, or the tick is missed.
			if (step == Step.EQUIPPED) wait = delay.get();
			return;
		}
	}

	private enum Step {
		NONE, EQUIPPED, WAITING
	}

	/** Puts on a better piece for {@code slot} if you have one. */
	private Step equipBest(EquipmentSlot slot) {
		ItemStack worn = mc.player.getItemBySlot(slot);
		if (slot == EquipmentSlot.CHEST && awaitLanding.get() && mc.player.isFallFlying() && ItemInfo.canGlide(worn)) return Step.NONE;
		// No Durability takes the elytra off for a moment while you fly; it puts it back itself.
		if (slot == EquipmentSlot.CHEST && ElytraFlight.holdsChest()) return Step.NONE;
		if (ItemInfo.isBound(worn)) return Step.NONE;
		double current = score(worn, slot);
		int best = -1;
		double bestScore = current, bestFresh = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack s = mc.player.getInventory().getItem(i);
			if (s.isEmpty() || ItemInfo.armorSlot(s) != slot) continue;
			// Better than what's worn by more than wear, or it stays on: a piece is used until Preserve takes it off, not
			// swapped for a fresher copy at every point of wear (which wore all your spares in turn). Among the better
			// ones, the freshest.
			double sc = score(s, slot);
			if (sc <= current) continue;
			double fresh = ItemInfo.durabilityFraction(s);
			if (best < 0 || sc > bestScore || sc == bestScore && fresh > bestFresh) {
				best = i;
				bestScore = sc;
				bestFresh = fresh;
			}
		}
		if (best < 0) return wornDown(worn) ? takeOff(slot, worn) : Step.NONE;
		// Grim (2b2t) cancels a click while you move: your keys are let go for a tick first.
		if (!Myriad.inventory().prepareClick()) return Step.WAITING;
		return Myriad.inventory().move(best, ItemInfo.inventoryIndex(slot)) ? Step.EQUIPPED : Step.WAITING;
	}

	/** Takes a worn-down piece off, with nothing to put on instead, into an empty slot (an elytra only on the ground). */
	private Step takeOff(EquipmentSlot slot, ItemStack worn) {
		if (ItemInfo.canGlide(worn) && (mc.player.isFallFlying() || !mc.player.onGround())) return Step.NONE;
		var inv = mc.player.getInventory();
		int to = -1;
		for (int i = Slots.MAIN_START; i < Slots.MAIN_END && to < 0; i++) if (inv.getItem(i).isEmpty()) to = i;
		for (int i = 0; i < 9 && to < 0; i++) if (i != inv.getSelectedSlot() && i != Myriad.inventory().serverSlot() && inv.getItem(i).isEmpty()) to = i;
		if (to < 0) {
			if (!warnedFull) warn("No room to take off " + worn.getHoverName().getString() + " before it breaks.");
			warnedFull = true;
			return Step.NONE;
		}
		if (!Myriad.inventory().prepareClick()) return Step.WAITING;
		String name = worn.getHoverName().getString();
		int uses = ItemInfo.durability(worn);
		if (!Myriad.inventory().move(to, ItemInfo.inventoryIndex(slot))) return Step.WAITING;
		info("Took off " + name + " (" + uses + " uses left)");
		warnedFull = false;
		return Step.EQUIPPED;
	}

	/** Higher is better, wear aside; 0 for nothing worth wearing (or put aside to be mended). */
	private double score(ItemStack s, EquipmentSlot slot) {
		if (s.isEmpty()) return 0;
		// Put aside to be mended (a Preserve: here, Auto Tool's, Recast's or Altitude's).
		if (ItemInfo.isBound(s) || Myriad.inventory().isSpared(s)) return 0;
		if (slot == EquipmentSlot.CHEST) {
			boolean elytra = ItemInfo.canGlide(s);
			// The wanted kind always beats the other; the other still beats an empty slot.
			boolean wanted = elytra == (chest.get() == Chest.ELYTRA);
			if (elytra) return wanted ? 1000 : 0.5;
			if (!wanted) return 0.5 + armorValue(s, slot) / 1000;
		}
		// Pumpkins and heads go in the head slot but aren't armour.
		if (ItemInfo.armor(s, slot) <= 0) return 0;
		return 1 + armorValue(s, slot);
	}

	private double armorValue(ItemStack s, EquipmentSlot slot) {
		double armor = ItemInfo.armor(s, slot), toughness = ItemInfo.toughness(s, slot);
		double protection = ItemInfo.enchantmentLevel(s, Enchantments.PROTECTION);
		double preferred = ItemInfo.enchantmentLevel(s, preferredKey(slot));
		return armor * 10 + toughness * 4 + protection * 2 + preferred * 3;
	}

	private ResourceKey<Enchantment> preferredKey(EquipmentSlot slot) {
		return switch (prefer.get()) {
			case BLAST_LEGS -> slot == EquipmentSlot.LEGS ? Enchantments.BLAST_PROTECTION : Enchantments.PROTECTION;
			case PROTECTION -> Enchantments.PROTECTION;
			case BLAST -> Enchantments.BLAST_PROTECTION;
			case FIRE -> Enchantments.FIRE_PROTECTION;
			case PROJECTILE -> Enchantments.PROJECTILE_PROTECTION;
		};
	}

}
