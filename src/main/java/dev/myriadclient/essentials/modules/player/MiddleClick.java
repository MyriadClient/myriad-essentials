package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.util.Interactions;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Middle click does something useful depending on what you're pointing at: in the air, at an entity, at a block, or
 * while flying with an elytra. Each can add or remove a friend, throw experience bottles (for as long as you hold the
 * button), launch a rocket or throw a pearl. Items are used with a silent swap, so your selected slot doesn't change,
 * and without your hand swinging on screen (the server still gets the swing vanilla sends with a use); vanilla's pick
 * block is skipped when an action runs.
 */
public class MiddleClick extends Module {
	public enum Action {
		NONE, FRIEND, XP, ROCKET, PEARL
	}

	private final EnumSetting<Action> air = sgGeneral.oneOf("Air", Action.PEARL).description("Pointing at nothing.").build();
	private final EnumSetting<Action> entity = sgGeneral.oneOf("Entity", Action.FRIEND).description("Pointing at an entity (Friend only works on players).").build();
	private final EnumSetting<Action> block = sgGeneral.oneOf("Block", Action.XP).description("Pointing at a block.").build();
	private final EnumSetting<Action> flying = sgGeneral.oneOf("Flying", Action.ROCKET).description("Flying with an elytra, whatever you point at.").build();
	private final IntSetting xpDelay = sgGeneral.integer("XP Delay").description("Ticks between experience bottles while held.").defaultValue(0).range(0, 10).build();
	private final BoolSetting fromInventory = sgGeneral.bool("From Inventory").description("Use the item from your inventory when it isn't in the hotbar (it's put back after).").defaultValue(true).build();
	private final BoolSetting whileUsing = sgGeneral.bool("While Using").description("Allow actions while eating or blocking.").build();

	private boolean held;
	private int xpWait;
	/**
	 * An item to use once it can be brought from the inventory (a click waits while you move), for up to
	 * {@link #PENDING_TICKS} after the click; a use that late would surprise you, so it's dropped then.
	 */
	private Item pending;
	private int pendingTicks;
	private static final int PENDING_TICKS = 5;

	public MiddleClick() {
		super(Tags.PLAYER, "Middle Click", "Friends, experience, rockets or pearls on the middle mouse button.");
	}

	@Override
	protected void onDisable() {
		held = false;
		pending = null;
	}

	@Subscribe
	private void onMouse(MouseButtonEvent e) {
		if (e.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || e.inScreen()) return;
		if (e.action() == GLFW.GLFW_RELEASE) {
			held = false;
			return;
		}
		if (!e.isPress() || !inGame()) return;
		Action action = current();
		if (action == Action.NONE) return;
		// Run the action instead of vanilla's pick block.
		e.cancel();
		if (mc.player.isUsingItem() && !whileUsing.get()) return;
		held = true;
		xpWait = 0;
		run(action, true);
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (pending != null && inGame()) {
			if (--pendingTicks < 0) pending = null;
			else use(pending);
		}
		if (!held || !inGame()) return;
		if (mc.gui.screen() != null || GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) != GLFW.GLFW_PRESS) {
			held = false;
			return;
		}
		if (mc.player.isUsingItem() && !whileUsing.get()) return;
		// Only experience repeats while the button is held.
		Action action = current();
		if (action == Action.XP && --xpWait < 0) run(action, false);
	}

	/** The action for what you're pointing at right now. */
	private Action current() {
		if (mc.player.isFallFlying()) return flying.get();
		HitResult hit = mc.hitResult;
		if (hit == null || hit.getType() == HitResult.Type.MISS) return air.get();
		if (hit.getType() == HitResult.Type.ENTITY) {
			Action a = entity.get();
			return a == Action.FRIEND && !(((EntityHitResult) hit).getEntity() instanceof Player) ? Action.NONE : a;
		}
		return block.get();
	}

	private void run(Action action, boolean press) {
		switch (action) {
			case FRIEND -> {
				if (press && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player p) toggleFriend(p);
			}
			case XP -> {
				use(Items.EXPERIENCE_BOTTLE);
				xpWait = xpDelay.get();
			}
			case ROCKET -> use(Items.FIREWORK_ROCKET);
			case PEARL -> use(Items.ENDER_PEARL);
			case NONE -> {
			}
		}
	}

	private void toggleFriend(Player p) {
		String name = p.getGameProfile().name();
		if (Myriad.friends().isFriend(name)) {
			Myriad.friends().remove(name);
			info("Removed " + name + " from friends");
		} else {
			Myriad.friends().add(name);
			info("Added " + name + " to friends");
		}
	}

	/**
	 * Uses {@code item} from the off hand or the hotbar, without changing the visible slot; or borrowed from the
	 * inventory into any hotbar slot but the selected one (what was there swaps places with it, and both go back after).
	 * While you move the click can wait a few ticks, and the use with it.
	 */
	private void use(Item item) {
		if (mc.player.getOffhandItem().is(item)) {
			pending = null;
			Interactions.useItem(InteractionHand.OFF_HAND);
			swing(InteractionHand.OFF_HAND);
			return;
		}
		int slot = Myriad.inventory().findInHotbar(s -> s.is(item));
		if (slot < 0 && fromInventory.get()) {
			int from = Myriad.inventory().findInInventory(s -> s.is(item));
			if (from < 0) {
				pending = null;
				return;
			}
			slot = Myriad.inventory().borrow(this, from, s -> true);
			if (slot < 0) {
				if (pending == null) pendingTicks = PENDING_TICKS;
				pending = item;
				return;
			}
		}
		pending = null;
		if (slot < 0) return;
		// Keeps it if it's borrowed (it goes back once you stop using it).
		Myriad.inventory().borrow(this, slot, null);
		Myriad.inventory().silentSwap(slot, () -> {
			Interactions.useItem(InteractionHand.MAIN_HAND);
			swing(InteractionHand.MAIN_HAND);
		});
	}

	/** The swing vanilla sends with a use, without the hand moving on screen; none where the server wants none (2b2t). */
	private void swing(InteractionHand hand) {
		if (mc.getConnection() != null && AntiCheat.inEffect().checksSwings()) mc.getConnection().send(new ServerboundSwingPacket(hand));
	}
}
