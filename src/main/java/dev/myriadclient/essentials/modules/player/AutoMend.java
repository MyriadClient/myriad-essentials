package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.util.Interactions;
import dev.myriadclient.api.util.ItemInfo;
import dev.myriadclient.api.util.Slots;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayDeque;

/**
 * Mends your gear with bottles o' enchanting: thrown straight down at your feet (the throw is aimed down, whatever
 * way you look; your view doesn't move) while something
 * with Mending that you wear or hold is damaged, about as many as the damage needs. Bottles still in the air and orbs
 * on the ground are counted, so it doesn't throw more while they land. With Repair Inventory, damaged Mending armour
 * from your inventory is worn in turn and Mending tools are held in turn (the server holds them; your hand doesn't
 * change), and everything goes back where it was afterwards. Turns itself off once nothing's left to mend.
 */
public class AutoMend extends Module {
	private final IntSetting perTick = sgGeneral.integer("Bottles Per Tick").description("Most bottles thrown each tick.")
		.defaultValue(2).range(1, 6).build();
	private final BoolSetting inventory = sgGeneral.bool("Repair Inventory").description("Also mend damaged Mending gear from your inventory: "
		+ "armour is worn in turn, tools are held in turn, and everything goes back afterwards.").defaultValue(true).build();

	/** Durability one bottle mends on average (3-11 experience, 2 durability each). */
	private static final int PER_BOTTLE = 14;
	/** The look the throws need; Grim refuses past ±90. */
	private static final float DOWN = 89.9f;
	/** Ticks with nothing to throw and nothing landing before it's done. */
	private static final int IDLE_TICKS = 10;

	/** Armour moves made to wear inventory pieces: {from, to} inventory indexes, undone in reverse. */
	private final ArrayDeque<int[]> moved = new ArrayDeque<>();
	private int bottleSlot = -1, heldSlot = -1, idle, thrown;
	private boolean throwing;

	public AutoMend() {
		super(Tags.PLAYER, "Auto Mend", "Mends your gear with bottles o' enchanting.");
	}

	@Override
	protected void onEnable() {
		moved.clear();
		heldSlot = -1;
		idle = 0;
		thrown = 0;
	}

	@Override
	protected void onDisable() {
		Myriad.inventory().release(this);
		Myriad.inventory().giveBack(this);
		throwing = false;
		if (!moved.isEmpty()) warn("Turned off before your armour was put back: it's still on");
		moved.clear();
	}

	/** Plans at the start of the tick: what to mend next, and the look for this tick's throws. */
	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (mc.player.containerMenu != mc.player.inventoryMenu) return;
		int damage = equippedDamage();
		int landing = landing();
		throwing = damage > landing;
		if (throwing) {
			idle = 0;
			bottleSlot = bottles();
			if (bottleSlot < 0) {
				warn("No bottles o' enchanting left" + (thrown > 0 ? " (threw " + thrown + ")" : ""));
				disable();
				return;
			}
			return;
		}
		if (landing > 0 || damage > 0) return;
		// Everything worn and held is mended: the next piece from your inventory, or put things back and stop.
		if (inventory.get() && bringNext()) return;
		if (restore() && ++idle > IDLE_TICKS) {
			if (thrown > 0) info("Mended (" + thrown + " bottles)");
			disable();
		}
	}

	/**
	 * Throws at the start of the tick, before the movement packet. A use packet carries its own aim, which the server
	 * throws along and core makes this tick's movement packet carry too (Grim wants them equal), so aiming the throw
	 * itself down is all it takes: the camera's pitch is set down just for the call.
	 */
	@Subscribe(priority = 880, inGame = true)
	private void onThrowTime(TickEvent.Pre e) {
		if (!throwing || bottleSlot < 0) return;
		int n = Math.min(perTick.get(), Math.max(1, (equippedDamage() - landing() + PER_BOTTLE - 1) / PER_BOTTLE));
		for (int i = 0; i < n; i++) {
			if (!Myriad.limits().canSend(PacketLimits.Kind.INTERACT, 1) || !mc.player.getInventory().getItem(bottleSlot).is(Items.EXPERIENCE_BOTTLE)) break;
			Myriad.inventory().silentSwap(bottleSlot, this::throwDown);
			thrown++;
		}
	}

	private void throwDown() {
		float pitch = mc.player.getXRot(), oldPitch = mc.player.xRotO;
		mc.player.setXRot(DOWN);
		try {
			Interactions.useItem(InteractionHand.MAIN_HAND);
		} finally {
			mc.player.setXRot(pitch);
			mc.player.xRotO = oldPitch;
		}
	}

	/** Damage left on what Mending can reach: what you wear, the off hand, and the item the server holds in your hand. */
	private int equippedDamage() {
		int d = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
			d += mendable(mc.player.getItemBySlot(slot));
		}
		return d + mendable(Myriad.inventory().serverItem());
	}

	private static int mendable(ItemStack s) {
		return !s.isEmpty() && s.isDamaged() && ItemInfo.hasEnchantment(s, Enchantments.MENDING) ? s.getDamageValue() : 0;
	}

	/** Durability the bottles in the air and the orbs around you will mend. */
	private int landing() {
		var box = mc.player.getBoundingBox().inflate(8);
		int xp = 0;
		for (ExperienceOrb orb : mc.level.getEntitiesOfClass(ExperienceOrb.class, box)) xp += orb.getValue();
		int bottles = mc.level.getEntitiesOfClass(ThrownExperienceBottle.class, box).size();
		return xp * 2 + bottles * PER_BOTTLE;
	}

	/** A hotbar slot with bottles, borrowing a stack from the inventory if none is there; -1 if you have none. */
	private int bottles() {
		int slot = Myriad.inventory().findInHotbar(s -> s.is(Items.EXPERIENCE_BOTTLE));
		if (slot >= 0) return slot;
		int index = Myriad.inventory().findInInventory(s -> s.is(Items.EXPERIENCE_BOTTLE));
		if (index < 0) return -1;
		return Myriad.inventory().borrow(this, index, s -> !mendableAnywhere(s));
	}

	private static boolean mendableAnywhere(ItemStack s) {
		return mendable(s) > 0;
	}

	/**
	 * Wears or holds the next damaged Mending piece from the inventory. Armour is moved into its slot (swapping what's
	 * there out); a hotbar tool is held by the server. True while there's something being brought in.
	 */
	private boolean bringNext() {
		var inv = mc.player.getInventory();
		for (int i = 0; i < Slots.MAIN_END; i++) {
			ItemStack s = inv.getItem(i);
			if (mendable(s) == 0) continue;
			EquipmentSlot armour = ItemInfo.armorSlot(s);
			if (armour != null && armour != EquipmentSlot.MAINHAND && armour != EquipmentSlot.OFFHAND && armour != EquipmentSlot.BODY) {
				int to = ItemInfo.inventoryIndex(armour);
				// Bound gear can't come off to make room.
				if (ItemInfo.isBound(inv.getItem(to))) continue;
				if (!Myriad.inventory().prepareClick()) return true;
				if (Myriad.inventory().move(i, to)) moved.push(new int[]{i, to});
				return true;
			}
			if (Slots.isHotbar(i)) {
				if (Myriad.inventory().hold(this, i, 200)) heldSlot = i;
				return true;
			}
		}
		Myriad.inventory().release(this);
		heldSlot = -1;
		return false;
	}

	/** Puts the worn pieces back where they came from, one move a tick. True once everything's back. */
	private boolean restore() {
		if (moved.isEmpty()) return true;
		if (!Myriad.inventory().prepareClick()) return false;
		int[] m = moved.peek();
		if (Myriad.inventory().move(m[1], m[0])) moved.pop();
		return moved.isEmpty();
	}
}
