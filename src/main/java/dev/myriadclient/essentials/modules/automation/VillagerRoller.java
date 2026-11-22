package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.ActionSetting;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.essentials.util.NamedChoiceSetting;
import dev.myriadclient.essentials.util.VillagerTrades;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rerolls a librarian until it sells the enchanted book you want, at its highest level: its trades are read (the server
 * only sends them with the trading screen, which stays hidden), and
 * if the book isn't there, or costs too much, its lectern is broken, it's waited on to lose its job, the lectern goes
 * back and it's waited on to take the job again, with new trades. Once the book is there it's locked in by buying one
 * trade (one you can pay for, a sale of yours first), so its trades stay; then it stops.
 * <p>
 * The villager is the nearest librarian or jobless villager within reach that nobody has traded with yet (one that has
 * keeps its trades for good), the lectern the one nearest it within reach. It's broken with your best tool and picked
 * back up where it falls (a step or two away and back, sneaking, if it lands out of reach).
 */
public class VillagerRoller extends Module {
	private final NamedChoiceSetting enchantment = sgGeneral.add(new NamedChoiceSetting("Enchantment", "The enchanted book to roll for, at its "
		+ "highest level.", "minecraft:mending", null, this::enchantments, NamedChoiceSetting.translated(id -> "enchantment." + id.getNamespace() + "." + id.getPath())));
	@SuppressWarnings("unused")
	private final ActionSetting forgetTrades = sgGeneral.action("Forget Known Trades", () -> info("Forgot " + VillagerTrades.forgetAll()
		+ " villagers' trades on this server")).description("Trades are remembered per villager once seen (until its level or job changes): this "
		+ "forgets them all here, so each is looked at again.").build();
	private final BoolSetting lock = sgGeneral.bool("Lock Trade").defaultValue(true)
		.description("Once it's found, buy one trade (one you can pay for, selling first) so the villager keeps its trades.").build();

	private enum Step { FIND, LOOK, TRADES, BREAK, COLLECT, BACK, JOBLESS, PLACE, HIRED, LOCK, DONE }

	/** Ticks each wait may take before it's given up on (and started over). */
	private static final int JOB_TICKS = 600, TRADES_TICKS = 60, BREAK_TICKS = 200, PLACE_TICKS = 100, WALK_TICKS = 200;
	/** Ticks a lectern that fell out of pickup range lies before it's walked to. */
	private static final int PICKUP_TICKS = 15;

	private Step step = Step.FIND;
	private int ticks;
	private @Nullable Villager villager;
	private @Nullable BlockPos lectern;
	/** Where you stood: walked back to after fetching the lectern, so it keeps falling in reach. */
	private @Nullable Vec3 home;
	/** Where you're being walked to (the fallen lectern, then home), or null. */
	private @Nullable Vec3 walkTo;
	/** The trades the villager showed last, and whether anyone had traded with it. */
	private @Nullable MerchantOffers offers;
	private boolean traded;
	private int rolls;

	public VillagerRoller() {
		super(Tags.AUTOMATION, "Villager Roller", "Rerolls a librarian until it sells the book you want.");
	}

	@Override
	protected void onEnable() {
		go(Step.FIND);
		rolls = 0;
		villager = null;
		lectern = null;
	}

	@Override
	protected void onDisable() {
		walkTo = null;
		Myriad.breaking().cancel(this);
		Myriad.placement().cancel(this);
		closeTrades();
	}

	private void go(Step next) {
		step = next;
		ticks = 0;
	}

	/** Every enchantment the server has, by id. */
	private List<String> enchantments() {
		List<String> ids = new ArrayList<>();
		if (mc.level != null) mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElementIds()
			.forEach(k -> ids.add(k.identifier().toString()));
		if (ids.isEmpty()) ids.add("minecraft:mending");
		ids.sort(null);
		return ids;
	}

	// ---- Finding ------------------------------------------------------------------------------------------------

	private static boolean jobless(Villager v) {
		return v.getVillagerData().profession().is(VillagerProfession.NONE);
	}

	private static boolean librarian(Villager v) {
		return v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
	}

	/** The nearest grown librarian or jobless villager in reach. */
	private @Nullable Villager findVillager() {
		Vec3 eyes = Reach.eyes();
		Villager best = null;
		double bestDist = Double.MAX_VALUE;
		for (Villager v : mc.level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(6))) {
			if (v.isBaby() || !(librarian(v) || jobless(v))) continue;
			double d = v.position().distanceToSqr(eyes);
			if (d < bestDist && d <= Reach.blockRange() * Reach.blockRange()) {
				bestDist = d;
				best = v;
			}
		}
		return best;
	}

	/** The lectern nearest {@code v} that you can reach. */
	private @Nullable BlockPos findLectern(Villager v) {
		BlockPos at = v.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 2, 4))) {
			if (!mc.level.getBlockState(p).is(Blocks.LECTERN) || !Reach.canReach(p)) continue;
			double d = p.distSqr(at);
			if (d < bestDist) {
				bestDist = d;
				best = p.immutable();
			}
		}
		return best;
	}

	// ---- Ticks --------------------------------------------------------------------------------------------------

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		ticks++;
		if (step != Step.FIND && step != Step.DONE && (villager == null || !villager.isAlive())) {
			warn("The villager is gone");
			toggle();
			return;
		}
		switch (step) {
			case FIND -> find();
			case LOOK -> {
				// Known not to have it (VillagerTrades): rolled again without opening it.
				if (ticks == 1 && knownWithout(villager)) {
					rolls++;
					go(Step.BREAK);
					return;
				}
				// A tick for the look, then the trades are asked for (see openTrades).
				if (ticks > TRADES_TICKS) go(Step.LOOK);
				else if (ticks == 1) openTrades();
			}
			case TRADES -> {
				if (offers != null) judge();
				else if (ticks > TRADES_TICKS) go(Step.LOOK);
			}
			case BREAK -> breakLectern();
			case COLLECT -> collect();
			case BACK -> {
				walkTo = home;
				if (home == null || mc.player.position().distanceToSqr(home) < 0.3 * 0.3 || ticks > WALK_TICKS) {
					walkTo = null;
					go(Step.JOBLESS);
				}
			}
			case JOBLESS -> {
				if (jobless(villager)) go(Step.PLACE);
				else if (ticks > JOB_TICKS) {
					warn("The villager kept its job without the lectern: placing it back");
					go(Step.PLACE);
				}
			}
			case PLACE -> placeLectern();
			case HIRED -> {
				if (librarian(villager)) go(Step.LOOK);
				else if (ticks > JOB_TICKS) {
					warn("The villager didn't take the lectern: is it its nearest, and can it walk to it?");
					toggle();
				}
			}
			case LOCK -> {
			}
			case DONE -> toggle();
		}
	}

	private void find() {
		villager = findVillager();
		if (villager == null) {
			warn("No librarian or jobless villager in reach");
			toggle();
			return;
		}
		lectern = findLectern(villager);
		if (lectern == null) {
			warn("No lectern in reach near the villager");
			toggle();
			return;
		}
		home = mc.player.position();
		go(librarian(villager) ? Step.LOOK : Step.HIRED);
	}

	// ---- Trades -------------------------------------------------------------------------------------------------

	/** Looks at the villager, and once the server has the look, opens its trades (their screen stays hidden). */
	private void openTrades() {
		if (mc.player.containerMenu != mc.player.inventoryMenu || mc.gui.screen() != null) {
			go(Step.LOOK);
			return;
		}
		Villager v = villager;
		Vec3 at = v.getBoundingBox().getCenter();
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 d = at.subtract(eyes);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance()));
		offers = null;
		Myriad.rotations().request(this, yaw, pitch, Rotations.PRIORITY_HIGH, Rotations.Options.INSTANT, () -> {
			if (step != Step.LOOK || !isEnabled() || mc.player == null) return;
			mc.gameMode.interact(mc.player, v, new EntityHitResult(v, at), InteractionHand.MAIN_HAND);
			go(Step.TRADES);
		});
	}

	@Subscribe(packets = ClientboundMerchantOffersPacket.class)
	private void onOffers(PacketEvent.Receive e) {
		if (step != Step.TRADES || !(e.packet() instanceof ClientboundMerchantOffersPacket p)) return;
		offers = p.getOffers();
		traded = p.getVillagerXp() > 0 || p.getVillagerLevel() > 1;
		if (villager != null) VillagerTrades.record(villager, offers);
	}

	/** Whether {@code v}'s known trades (see VillagerTrades) are without the book wanted: then it's rolled without opening it. */
	private boolean knownWithout(Villager v) {
		VillagerTrades.Known known = VillagerTrades.of(v);
		if (known == null) return false;
		int top = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
			.filter(h -> h.key().identifier().toString().equals(enchantment.get())).findFirst().map(h -> h.value().getMaxLevel()).orElse(1);
		for (VillagerTrades.Trade t : known.trades()) if (t.gives("minecraft:enchanted_book", enchantment.get(), top)) return false;
		return true;
	}

	/** The trades are in: the book's there (lock it in and stop), or the villager's rolled again. */
	private void judge() {
		if (traded) {
			closeTrades();
			warn("Someone has traded with this villager: its trades can't change any more");
			toggle();
			return;
		}
		int found = wanted(offers);
		if (found < 0) {
			closeTrades();
			rolls++;
			go(Step.BREAK);
			return;
		}
		String what = describe(offers.get(found));
		if (!lock.get()) {
			closeTrades();
			info("Found " + what + " after " + rolls + " rolls");
			go(Step.DONE);
			return;
		}
		go(Step.LOCK);
		lockIn(found, what);
	}

	/** The offer selling the wanted book at its highest level, or -1. */
	private int wanted(MerchantOffers list) {
		for (int i = 0; i < list.size(); i++) {
			MerchantOffer o = list.get(i);
			ItemStack result = o.getResult();
			if (!result.is(Items.ENCHANTED_BOOK)) continue;
			ItemEnchantments stored = result.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
			for (var entry : stored.entrySet()) {
				Holder<Enchantment> holder = entry.getKey();
				if (holder.unwrapKey().isEmpty() || !holder.unwrapKey().get().identifier().toString().equals(enchantment.get())) continue;
				if (entry.getIntValue() >= holder.value().getMaxLevel()) return i;
			}
		}
		return -1;
	}

	private static String describe(MerchantOffer o) {
		ItemEnchantments stored = o.getResult().getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
		StringBuilder b = new StringBuilder();
		for (var entry : stored.entrySet()) {
			if (!b.isEmpty()) b.append(", ");
			b.append(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
		}
		return String.format(Locale.ROOT, "%s for %d emeralds", b, o.getCostA().getCount());
	}

	/** How many of the stack {@code cost} asks for your inventory has (by item and components). */
	private int have(ItemStack cost) {
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (ItemStack.isSameItemSameComponents(inv.getItem(i), cost)) n += inv.getItem(i).getCount();
		return n;
	}

	private boolean affordable(MerchantOffer o) {
		if (o.isOutOfStock() || have(o.getCostA()) < o.getCostA().getCount()) return false;
		ItemStack b = o.getCostB();
		return b.isEmpty() || have(b) >= b.getCount();
	}

	/**
	 * Buys one trade so the villager keeps its trades: one of yours it buys first (you get emeralds), else the book. One
	 * trade only: the result is picked up and put down in an empty slot (a shift-click would trade again and again).
	 */
	private void lockIn(int book, String what) {
		MerchantOffers list = offers;
		int pick = -1;
		for (int i = 0; i < list.size() && pick < 0; i++) if (list.get(i).getResult().is(Items.EMERALD) && affordable(list.get(i))) pick = i;
		if (pick < 0 && affordable(list.get(book))) pick = book;
		if (pick < 0 || !(mc.player.containerMenu instanceof MerchantMenu menu)) {
			closeTrades();
			warn("Found " + what + " after " + rolls + " rolls, but couldn't lock it in: nothing you can pay for");
			go(Step.DONE);
			return;
		}
		int empty = emptySlot(menu);
		if (empty < 0 || !Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 2) || !Myriad.inventory().prepareClick()) {
			closeTrades();
			warn("Found " + what + " after " + rolls + " rolls, but couldn't lock it in: no room or no moment to click");
			go(Step.DONE);
			return;
		}
		// As the trading screen does: the payment moved in, the trade picked, then the result taken once.
		menu.setSelectionHint(pick);
		menu.tryMoveItems(pick);
		mc.getConnection().send(new ServerboundSelectTradePacket(pick));
		mc.gameMode.handleContainerInput(menu.containerId, 2, 0, ContainerInput.PICKUP, mc.player);
		mc.gameMode.handleContainerInput(menu.containerId, empty, 0, ContainerInput.PICKUP, mc.player);
		closeTrades();
		info("Found " + what + " after " + rolls + " rolls, and locked it in");
		go(Step.DONE);
	}

	/** An empty slot of your inventory in the trading menu, or -1. */
	private int emptySlot(MerchantMenu menu) {
		for (Slot s : menu.slots) if (s.container == mc.player.getInventory() && !s.hasItem()) return s.index;
		return -1;
	}

	private void closeTrades() {
		if (mc.player != null && mc.player.containerMenu instanceof MerchantMenu) mc.player.closeContainer();
	}

	/** The trading screen stays hidden while the trades are read and locked. */
	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (e.screen() instanceof MerchantScreen && (step == Step.TRADES || step == Step.LOOK || step == Step.LOCK)) e.setScreen(null);
	}

	// ---- Lectern ------------------------------------------------------------------------------------------------

	private void breakLectern() {
		// Its trades are about to change.
		if (ticks == 1) VillagerTrades.forget(villager);
		if (!mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
			go(Step.COLLECT);
			return;
		}
		if (ticks > BREAK_TICKS) {
			warn("Couldn't break the lectern");
			toggle();
			return;
		}
		if (!Myriad.breaking().isPending(lectern)) Myriad.breaking().breakBlock(this, lectern, 0, Breaking.Options.forServer());
	}

	/**
	 * The broken lectern picked up: it usually falls in reach, but may land a block or two out, so after a moment you're
	 * walked to it (sneaking, so never off an edge), then back.
	 */
	private void collect() {
		boolean got = Myriad.inventory().findInHotbar(st -> st.is(Items.LECTERN)) >= 0 || Myriad.inventory().findInInventory(st -> st.is(Items.LECTERN)) >= 0;
		if (got) {
			walkTo = null;
			go(Step.BACK);
			return;
		}
		if (ticks < PICKUP_TICKS) return;
		ItemEntity drop = null;
		double best = Double.MAX_VALUE;
		for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(lectern).inflate(4))) {
			if (!item.getItem().is(Items.LECTERN)) continue;
			double d = item.distanceToSqr(mc.player);
			if (d < best) {
				best = d;
				drop = item;
			}
		}
		if (drop == null || ticks > WALK_TICKS) {
			// Nowhere to be seen (picked up a moment ago, or gone): placing it says if it's missing.
			walkTo = null;
			go(Step.BACK);
			return;
		}
		walkTo = drop.position();
	}

	/** Walks you to {@link #walkTo} with the movement keys, sneaking, whichever way you face. */
	@Subscribe
	private void onInput(InputEvent e) {
		if (walkTo == null || !inGame() || (step != Step.COLLECT && step != Step.BACK)) return;
		Vec3 d = walkTo.subtract(mc.player.position());
		double yaw = Math.toRadians(mc.player.getYRot());
		double ahead = d.x * -Math.sin(yaw) + d.z * Math.cos(yaw);
		double left = d.x * Math.cos(yaw) + d.z * Math.sin(yaw);
		e.forward = ahead > 0.15;
		e.backward = ahead < -0.15;
		e.left = left > 0.15;
		e.right = left < -0.15;
		e.sneak = true;
		e.sprint = false;
		e.jump = false;
	}

	private void placeLectern() {
		if (mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
			go(Step.HIRED);
			return;
		}
		if (ticks > PLACE_TICKS) {
			warn("Couldn't place the lectern back: is it in your inventory?");
			toggle();
			return;
		}
		if (Myriad.placement().isPending(lectern)) return;
		int slot = Myriad.inventory().ensureInHotbar(s -> s.is(Items.LECTERN), -1);
		if (slot < 0) return;
		Myriad.placement().place(this, lectern, slot, Placement.Options.forServer());
	}
}
