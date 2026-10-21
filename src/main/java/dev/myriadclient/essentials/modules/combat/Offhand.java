package dev.myriadclient.essentials.modules.combat;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.combat.Threats;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.EntityEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingGroup;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.function.Predicate;

/**
 * Keeps an item in your off hand: a totem, an end crystal, a golden apple or a shield. Whatever you pick, it falls back
 * to a totem when it matters: at low health, while flying with an elytra, or when something nearby (crystals, beds,
 * anchors, creepers, players, a fall) could kill you. A totem that pops is replaced the same tick.
 * <p>
 * Sword Gap puts a golden apple in your off hand while you hold right click with a sword, so you can eat without
 * switching. A hotbar slot can also keep a spare totem that's selected when your health gets low.
 * <p>
 * For addon developers: whether it forces a totem is {@link #FORCE_TOTEM}'s to decide, from the reasons it found
 * (a crystal PvP addon that knows a bigger hit is coming, a server where a totem is wasted):
 *
 * <pre>{@code
 * ctx.hook(Offhand.FORCE_TOTEM).named("Totem before my crystal").handle(call -> aboutToDetonate() || call.proceed());
 * }</pre>
 */
public class Offhand extends Module {
	public enum Item {
		TOTEM, CRYSTAL, GAPPLE, SHIELD
	}

	public enum Gapple {
		ENCHANTED, NORMAL, BOTH
	}

	/**
	 * Why a totem might be forced now: health at or below Health, gliding with Elytra on, or (with Lethal) something
	 * nearby that could kill you, and the health those were judged on.
	 */
	public record Danger(float health, boolean lowHealth, boolean gliding, boolean lethal) {
		public boolean any() {
			return lowHealth || gliding || lethal;
		}
	}

	/** Whether to force a totem into the off hand now. The default: any of the reasons. */
	public static final Hook<Danger, Boolean> FORCE_TOTEM = Hook.of("myriad-essentials:force_totem", "Force totem",
		"Whether Offhand forces a totem into your off hand now.");

	private static final Predicate<ItemStack> TOTEM = s -> s.is(Items.TOTEM_OF_UNDYING);

	private final EnumSetting<Item> item = sgGeneral.enumSetting("Item", Item.TOTEM).description("What to keep in your off hand when you're safe.").build();
	private final IntSetting health = sgGeneral.intSetting("Health").description("Switch to a totem at or below this health.").defaultValue(14).range(0, 36)
		.visible(() -> item.get() != Item.TOTEM).build();
	private final BoolSetting elytra = sgGeneral.bool("Elytra").description("Always a totem while flying with an elytra.").defaultValue(true)
		.visible(() -> item.get() != Item.TOTEM).build();
	private final EnumSetting<Gapple> gappleMode = sgGeneral.enumSetting("Gapple", Gapple.BOTH).description("Which golden apples count.").build();
	private final BoolSetting swordGap = sgGeneral.bool("Sword Gap").description("A golden apple while you hold right click with a sword.").defaultValue(true).build();
	private final IntSetting delay = sgGeneral.intSetting("Delay").description("Ticks between swaps.").defaultValue(0).range(0, 20).build();
	private final BoolSetting mainHandTotem = sgGeneral.bool("Main Hand Totem").description("A totem you hold in your main hand counts: don't force another into the off hand.").build();
	private final BoolSetting notify = sgGeneral.bool("Notify").description("Say when one of your totems pops.").build();

	private final SettingGroup sgSafety = settings.group("Safety");
	private final BoolSetting lethal = sgSafety.bool("Lethal").description("Force a totem when something nearby could kill you: crystals (added up), "
		+ "beds, anchors, creepers, players or a fall.").defaultValue(true).build();
	private final DoubleSetting buffer = sgSafety.doubleSetting("Buffer").description("Extra health kept as a margin.").defaultValue(1).range(0, 6).decimals(1).visible(lethal::get).build();

	private final SettingGroup sgHotbar = settings.group("Hotbar Totem");
	private final BoolSetting hotbarTotem = sgHotbar.bool("Hotbar Totem").description("Keep a spare totem in a hotbar slot, selected at low health.").build();
	private final IntSetting hotbarSlot = sgHotbar.intSetting("Slot").description("Hotbar slot (1-9).").defaultValue(9).range(1, 9).visible(hotbarTotem::get).build();
	private final IntSetting hotbarHealth = sgHotbar.intSetting("Health").description("Select it at or below this health.").defaultValue(6).range(0, 36).visible(hotbarTotem::get).build();

	private int wait;
	private boolean popped;

	public Offhand() {
		super(Tags.COMBAT, "Offhand", "Keeps a totem, crystal, golden apple or shield in your off hand.");
	}

	@Override
	public String hudInfo() {
		if (mc.player == null) return null;
		return String.valueOf(Myriad.inventory().count(TOTEM));
	}

	@Subscribe
	private void onPop(EntityEvent.TotemPopped e) {
		if (e.entity() == mc.player) popped = true;
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (popped) {
			// A totem just popped: re-arm this tick, ignoring the delay.
			popped = false;
			wait = 0;
			if (notify.get()) info("Popped a totem, " + Myriad.inventory().count(TOTEM) + " left");
		}
		// Clicks in an open container would land in it, so wait until it's closed.
		if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen) && mc.player.containerMenu != mc.player.inventoryMenu) return;
		if (wait > 0) {
			wait--;
			return;
		}
		boolean danger = inDanger();
		if (hotbarTotem.get()) tickHotbarTotem(danger);

		// Already holding a totem in the main hand: that one saves you, so leave the off hand as it is.
		if (danger && mainHandTotem.get() && TOTEM.test(mc.player.getMainHandItem())) danger = false;
		Predicate<ItemStack> want = danger ? TOTEM : wanted();
		if (want.test(mc.player.getOffhandItem())) return;
		int slot = find(want);
		if (slot < 0 && want != TOTEM) {
			if (TOTEM.test(mc.player.getOffhandItem())) return;
			slot = find(TOTEM);
		}
		if (slot < 0) return;
		int from = slot;
		// A totem matters more than the packet budget; other items wait for room. While a click would have to wait for
		// you to stop, one from the hotbar goes over with the swap-hands key instead (see Inventory.swapWithOffhand).
		if (TOTEM.test(mc.player.getInventory().getItem(from))) Myriad.limits().urgent(() -> Myriad.inventory().swapWithOffhand(from));
		else if (!Myriad.inventory().swapWithOffhand(from)) return;
		wait = delay.get();
	}

	/** Whether to force a totem: low health, elytra flight, or a hit that could kill you (or what a hook says). */
	private boolean inDanger() {
		float hp = Threats.health();
		boolean low = item.get() != Item.TOTEM && hp <= health.get();
		boolean gliding = item.get() != Item.TOTEM && elytra.get() && mc.player.isFallFlying();
		// The lethal check is the costly one: skipped when another reason already forces a totem and nothing hooks it.
		if (!FORCE_TOTEM.hooked()) return low || gliding || lethal.get() && Threats.isLethal(buffer.getFloat());
		Danger danger = new Danger(hp, low, gliding, lethal.get() && Threats.isLethal(buffer.getFloat()));
		Boolean force = FORCE_TOTEM.call(this, danger, Danger::any);
		return force != null ? force : danger.any();
	}

	/** The off hand item when you're safe. */
	private Predicate<ItemStack> wanted() {
		if (swordGap.get() && mc.options.keyUse.isDown() && mc.player.getMainHandItem().is(ItemTags.SWORDS) && !lookingAtBlock()) return this::isGapple;
		return switch (item.get()) {
			case TOTEM -> TOTEM;
			case CRYSTAL -> s -> s.is(Items.END_CRYSTAL);
			case GAPPLE -> this::isGapple;
			case SHIELD -> s -> s.is(Items.SHIELD);
		};
	}

	/** Right clicking a block with a sword uses the block (a chest, a door), so don't hijack it for eating. */
	private boolean lookingAtBlock() {
		return mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK
			&& !mc.level.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos()).isAir();
	}

	private boolean isGapple(ItemStack s) {
		return switch (gappleMode.get()) {
			case ENCHANTED -> s.is(Items.ENCHANTED_GOLDEN_APPLE);
			case NORMAL -> s.is(Items.GOLDEN_APPLE);
			case BOTH -> s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE);
		};
	}

	private void tickHotbarTotem(boolean danger) {
		int hotbar = hotbarSlot.get() - 1;
		// The spare can wait for a moment you aren't moving.
		if (!TOTEM.test(mc.player.getInventory().getItem(hotbar)) && Myriad.inventory().safeToClick()) {
			int src = Myriad.inventory().findInInventory(TOTEM);
			if (src >= 0 && Myriad.inventory().moveToHotbar(src, hotbar)) wait = delay.get();
		}
		boolean select = danger || Threats.health() <= hotbarHealth.get();
		if (select && TOTEM.test(mc.player.getInventory().getItem(hotbar)) && mc.player.getInventory().getSelectedSlot() != hotbar) {
			Myriad.inventory().select(hotbar);
		}
	}

	/**
	 * Main inventory first (keeps the hotbar intact), then the hotbar if allowed; an inventory index or -1. While you
	 * move, the hotbar first: from there it doesn't wait for you to stop.
	 */
	private int find(Predicate<ItemStack> predicate) {
		int hotbar = Myriad.inventory().findInHotbar(predicate);
		// Don't take the spare hotbar totem for the off hand while it already holds one.
		if (hotbarTotem.get() && hotbar == hotbarSlot.get() - 1 && predicate == TOTEM && mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) hotbar = -1;
		if (hotbar >= 0 && !Myriad.inventory().safeToClick()) return hotbar;
		int slot = Myriad.inventory().findInInventory(predicate);
		return slot >= 0 ? slot : hotbar;
	}
}
