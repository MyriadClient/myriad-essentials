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
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.essentials.util.NamedChoiceSetting;
import dev.myriadclient.essentials.util.VillagerTrades;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.tags.EnchantmentTags;
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
 * if the book isn't there, costs more than you'll pay ({@link #price}), or its other first
 * trade isn't the one you want ({@link #secondTrade}), its lectern is broken, it's waited on to lose its job, the lectern goes
 * back and it's waited on to take the job again, with new trades. Once the book is there it's locked in by buying one
 * trade (one you can pay for, a sale of yours first), so its trades stay; then it stops.
 * <p>
 * The villager is the nearest librarian or jobless villager within reach that nobody has traded with yet (one that has
 * keeps its trades for good), the lectern the one nearest it within reach. It's broken with your best tool and picked
 * back up where it falls (a step or two away and back, sneaking, if it lands out of reach).
 */
public class VillagerRoller extends Module {
	// What's wanted at level 1, where it can be rolled.
	private final NamedChoiceSetting enchantment = sgGeneral.add(new NamedChoiceSetting("Enchantment", "The book to roll for. Any: whichever "
		+ "comes at Min Level.", "minecraft:mending", null, this::enchantments, named()));
	private final IntSetting minLevel = sgGeneral.integer("Min Level").defaultValue(0).range(0, 5)
		.description("The lowest level of the book that will do, or its highest if that's lower (Min Level 4 takes Mending I and "
			+ "Unbreaking III); 0 for only its highest.").build();
	private final EnumSetting<SecondTrade> secondTrade = sgGeneral.oneOf("Second Trade", SecondTrade.ANY)
		.description("Its other first trade, beside the book: Bookshelf (it sells bookshelves) or Paper (it buys paper).").build();

	private final SettingGroup sgPrice = settings.group("Price");
	private final EnumSetting<Price> price = sgPrice.oneOf("Price", Price.ANY)
		.description("What the book may cost: At Most Max Price, or Lowest, the least a librarian ever asks (2 emeralds plus 3 a level, "
			+ "doubled for Mending and the like; one roll in 55 of those with a level V book). The villager's own price, before "
			+ "curing or Hero of the Village take some off.").build();
	private final IntSetting maxPrice = sgPrice.integer("Max Price").defaultValue(30).range(1, 64)
		.description("The most emeralds the book may cost.").visible(() -> price.get() == Price.AT_MOST).build();
	private final IntSetting keepFiveUnder = sgPrice.integer("Keep Level V Under").defaultValue(0).range(0, 64)
		.description("A level V book cheaper than this is kept whatever the other settings say, any enchantment, any second trade, "
			+ "glass or not (under 18 comes down to 1 emerald once the villager is cured). 0 for off.").build();

	// What's wanted once it's traded with: drawn as it levels up, never rolled.
	private final SettingGroup sgLevel = settings.group("Level Up");
	private final IntSetting levelUpTo = sgLevel.integer("Level Up To").defaultValue(1).range(1, 5)
		.description("Once it's locked in, trade with it (sales of yours first, then the most experience for your emeralds) up to this "
			+ "level, then check what it drew. 1 to leave it at level 1.").build();
	private final EnumSetting<Glass> glass = sgLevel.oneOf("Glass", Glass.OFF)
		.description("Whether it must sell glass at level 3 (2 villagers in 3 draw it). Wanted: for every book but a level V at the "
			+ "lowest price. Not For Level V: for every book below level V.").visible(() -> levelUpTo.get() >= 3).build();

	private final SettingGroup sgOther = settings.group("Other");
	private final BoolSetting lock = sgOther.bool("Lock Trade").defaultValue(true)
		.description("Once it's found, buy one trade (one you can pay for, selling first) so the villager keeps its trades. Always, "
			+ "with Level Up To above 1.").visible(() -> levelUpTo.get() <= 1).build();
	private final BoolSetting logRolls = sgOther.bool("Log Rolls").defaultValue(false)
		.description("Say in chat what each roll offered: every book with its price and the lowest it could be, and the other trade.").build();
	@SuppressWarnings("unused")
	private final ActionSetting forgetTrades = sgOther.action("Forget Known Trades", () -> info("Forgot " + VillagerTrades.forgetAll()
		+ " villagers' trades on this server")).description("Trades are remembered per villager once seen (until its level or job changes): this "
		+ "forgets them all here, so each is looked at again.").build();

	/** What the book may cost. */
	public enum Price {
		/** Anything. */
		ANY,
		/** At most Max Price. */
		AT_MOST,
		/** The lowest a librarian asks for it. */
		LOWEST
	}

	private enum Step { FIND, LOOK, TRADES, BREAK, COLLECT, BACK, JOBLESS, PLACE, HIRED, LOCK, LEVEL_WAIT, DONE }

	/** Whether a librarian levelled up to 3 must sell glass. */
	public enum Glass {
		/** It needn't. */
		OFF,
		/** It must, unless its book is a level V at the lowest price. */
		WANTED,
		/** It must, unless its book is a level V. */
		NOT_FOR_LEVEL_V
	}

	/** A librarian's two first trades are two of: buying paper, selling an enchanted book, selling bookshelves. */
	public enum SecondTrade {
		/** Whatever the other one is. */
		ANY,
		/** It sells bookshelves (9 emeralds each). */
		BOOKSHELF,
		/** It buys paper (24 for an emerald). */
		PAPER
	}

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
	/** Placing the lectern, and stepped aside for it (see placeLectern): back to placing after the step. */
	private boolean placing;
	/** Where you're being walked to (the fallen lectern, then home), or null. */
	private @Nullable Vec3 walkTo;
	/** The trades the villager showed last, and whether anyone had traded with it. */
	private @Nullable MerchantOffers offers;
	private boolean traded;
	private int rolls;
	/** The villager's level and experience as its last trades showed them. */
	private int villagerLevel, villagerXp;
	/** Trading it up to Level Up To since it was locked in. */
	private boolean leveling;
	/** The book it was kept for: its level, price and the lowest it could have been, and what it's called. */
	private int keptLevel, keptPrice, keptLowest;
	private String kept = "";
	/** Experience a villager needs for each level (index = the level it's at). */
	private static final int[] NEXT_LEVEL_XP = {0, 10, 70, 150, 250};

	public VillagerRoller() {
		super(Tags.AUTOMATION, "Villager Roller", "Rerolls a librarian until it sells the book you want.");
	}

	/** Version 2: Lock Trade moved from General to Other. */
	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion < 2) saved.move("General", "Lock Trade", "Other", "Lock Trade");
	}

	@Override
	protected void onEnable() {
		go(Step.FIND);
		rolls = 0;
		leveling = false;
		placing = false;
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
		ids.addFirst(ANY);
		return ids;
	}

	private static final String ANY = "any";

	private static java.util.function.Function<String, String> named() {
		var translated = NamedChoiceSetting.translated(id -> "enchantment." + id.getNamespace() + "." + id.getPath());
		return id -> id.equals(ANY) ? "Any" : translated.apply(id);
	}

	/**
	 * Whether a book of {@code id} at {@code level} (its highest {@code top}) is one wanted, price aside: at Min Level or
	 * more, or at its highest where that's below Min Level (Mending I and Unbreaking III count at Min Level 4).
	 */
	private boolean bookWanted(String id, int level, int top) {
		if (!enchantment.get().equals(ANY) && !enchantment.get().equals(id)) return false;
		return level >= (minLevel.get() == 0 ? top : Math.min(minLevel.get(), top));
	}

	/** The highest level of the enchantment {@code id}, 1 if it isn't known. */
	private int topLevel(String id) {
		return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
			.filter(h -> h.key().identifier().toString().equals(id)).findFirst().map(h -> h.value().getMaxLevel()).orElse(1);
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
				if (ticks == 1 && !leveling && knownWithout(villager)) {
					rolls++;
					go(Step.BREAK);
					return;
				}
				// A tick for the look, then the trades are asked for (see openTrades).
				if (ticks > TRADES_TICKS) go(Step.LOOK);
				else if (ticks == 1) openTrades();
			}
			case TRADES -> {
				if (offers != null) {
					if (leveling) level();
					else judge();
				}
				else if (ticks > TRADES_TICKS) go(Step.LOOK);
			}
			case BREAK -> breakLectern();
			case COLLECT -> collect();
			case BACK -> {
				// Back where you stood, and clear of the lectern's block: stopping short on its side (you come back from where
				// it fell) can leave you half in it, where it can't be placed again.
				boolean inWay = inLecternsWay();
				walkTo = home == null ? null : inWay ? awayFromLectern() : home;
				if (walkTo == null || !inWay && mc.player.position().distanceToSqr(home) < 0.3 * 0.3 || ticks > WALK_TICKS) {
					walkTo = null;
					go(placing ? Step.PLACE : Step.JOBLESS);
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
			case LEVEL_WAIT -> {
				// It levels up a moment after the trade that got it there, while nobody's trading with it.
				// Short of the next level (just locked in): nothing to wait for, more trades first.
				boolean short_ = villagerLevel < 5 && villagerXp < NEXT_LEVEL_XP[villagerLevel];
				if (villager.getVillagerData().level() > villagerLevel || short_ && ticks > 5 || ticks > 200) go(Step.LOOK);
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
		villagerLevel = p.getVillagerLevel();
		villagerXp = p.getVillagerXp();
		traded = p.getVillagerXp() > 0 || p.getVillagerLevel() > 1;
		if (villager != null) VillagerTrades.record(villager, offers);
	}

	/** Whether {@code v}'s known trades (see VillagerTrades) are without the book wanted: then it's rolled without opening it. */
	private boolean knownWithout(Villager v) {
		VillagerTrades.Known known = VillagerTrades.of(v);
		if (known == null) return false;
		for (VillagerTrades.Trade t : known.trades()) {
			if (!t.item().equals("minecraft:enchanted_book")) continue;
			for (var e : t.enchantments().entrySet()) if (bookWanted(e.getKey(), e.getValue(), topLevel(e.getKey()))) return false;
		}
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
		if (logRolls.get()) info("Roll " + (rolls + 1) + ": " + summary(offers));
		int found = wanted(offers);
		if (found < 0) {
			closeTrades();
			rolls++;
			go(Step.BREAK);
			return;
		}
		String what = describe(offers.get(found));
		if (!lock.get() && levelUpTo.get() <= 1) {
			closeTrades();
			info("Found " + what + " after " + rolls + " rolls");
			go(Step.DONE);
			return;
		}
		go(Step.LOCK);
		lockIn(found, what);
	}

	/**
	 * The offer selling a wanted book (see {@link #bookWanted}) at a price you'll pay, from a villager whose other first
	 * trade is the one you want, or a level V book under Keep Level V Under whatever else; -1 if there's none. The book
	 * it's kept for is noted ({@link #keptLevel} and the rest), for the checks once it's levelled up.
	 */
	private int wanted(MerchantOffers list) {
		boolean secondOk = secondTradeOk(list);
		for (int i = 0; i < list.size(); i++) {
			MerchantOffer o = list.get(i);
			ItemStack result = o.getResult();
			if (!result.is(Items.ENCHANTED_BOOK)) continue;
			ItemEnchantments stored = result.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
			for (var entry : stored.entrySet()) {
				Holder<Enchantment> holder = entry.getKey();
				if (holder.unwrapKey().isEmpty()) continue;
				int level = entry.getIntValue(), price = o.getBaseCostA().getCount(), lowest = lowestPrice(holder, level);
				boolean keeper = keepFiveUnder.get() > 0 && level >= 5 && price < keepFiveUnder.get();
				if (!keeper) {
					if (!secondOk || !bookWanted(holder.unwrapKey().get().identifier().toString(), level, holder.value().getMaxLevel())) continue;
					if (this.price.get() == Price.LOWEST ? price > lowest : this.price.get() == Price.AT_MOST && price > maxPrice.get()) continue;
				}
				keptLevel = level;
				keptPrice = price;
				keptLowest = lowest;
				kept = Enchantment.getFullname(holder, level).getString();
				return i;
			}
		}
		return -1;
	}

	/** Whether the villager's other first trade (beside the book) is the one {@link #secondTrade} wants. */
	private boolean secondTradeOk(MerchantOffers list) {
		return switch (secondTrade.get()) {
			case ANY -> true;
			case BOOKSHELF -> list.stream().anyMatch(o -> o.getResult().is(Items.BOOKSHELF));
			case PAPER -> list.stream().anyMatch(o -> o.getBaseCostA().is(Items.PAPER));
		};
	}

	/**
	 * The lowest base price a librarian can ask for a book of {@code holder} at {@code level}: 2 emeralds plus 3 a level
	 * (it adds up to 4 plus 10 a level more at random), doubled for those in {@code #double_trade_price}, at most 64.
	 */
	static int lowestPrice(Holder<Enchantment> holder, int level) {
		int price = 2 + 3 * level;
		if (holder.is(EnchantmentTags.DOUBLE_TRADE_PRICE)) price *= 2;
		return Math.min(price, 64);
	}

	/** What a roll offered, for Log Rolls: each book with its price and the lowest it could be, and the other trades. */
	private static String summary(MerchantOffers list) {
		StringBuilder b = new StringBuilder();
		for (MerchantOffer o : list) {
			if (!b.isEmpty()) b.append("; ");
			ItemStack result = o.getResult();
			if (result.is(Items.ENCHANTED_BOOK)) {
				ItemEnchantments stored = result.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
				int lowest = 0;
				for (var entry : stored.entrySet()) {
					b.append(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString()).append(' ');
					lowest = Math.max(lowest, lowestPrice(entry.getKey(), entry.getIntValue()));
				}
				b.append(o.getBaseCostA().getCount()).append(" emeralds (lowest ").append(lowest).append(')');
			} else if (result.is(Items.BOOKSHELF)) b.append("sells bookshelves");
			else if (o.getBaseCostA().is(Items.PAPER)) b.append("buys paper");
			else b.append(result.getHoverName().getString());
		}
		return b.toString();
	}

	private static String describe(MerchantOffer o) {
		ItemEnchantments stored = o.getResult().getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
		StringBuilder b = new StringBuilder();
		for (var entry : stored.entrySet()) {
			if (!b.isEmpty()) b.append(", ");
			b.append(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
		}
		int lowest = 0;
		for (var entry : stored.entrySet()) lowest = Math.max(lowest, lowestPrice(entry.getKey(), entry.getIntValue()));
		return String.format(Locale.ROOT, "%s for %d emeralds (lowest %d)", b, o.getBaseCostA().getCount(), lowest);
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
		// A bookshelf seller (no sale of yours to make) with a book you can't pay for yet: a bookshelf does as well.
		for (int i = 0; i < list.size() && pick < 0; i++) if (affordable(list.get(i))) pick = i;
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
		villagerXp += Math.max(1, list.get(pick).getXp());
		closeTrades();
		info("Found " + what + " after " + rolls + " rolls, and locked it in");
		if (levelUpTo.get() > 1) {
			leveling = true;
			info("Trading it up to level " + levelUpTo.get());
			go(Step.LEVEL_WAIT);
		} else go(Step.DONE);
	}

	/**
	 * Levelling up: the trades are in. At Level Up To, the trades it drew are checked; otherwise it's traded with, one
	 * trade a tick, until it has the experience for the next level, then left a moment to level up.
	 */
	private void level() {
		if (villagerLevel >= levelUpTo.get() || villagerLevel >= 5) {
			closeTrades();
			verdict();
			go(Step.DONE);
			return;
		}
		if (villagerXp >= NEXT_LEVEL_XP[villagerLevel]) {
			closeTrades();
			go(Step.LEVEL_WAIT);
			return;
		}
		if (!(mc.player.containerMenu instanceof MerchantMenu menu)) {
			go(Step.LOOK);
			return;
		}
		int pick = levelTrade(offers);
		if (pick < 0) {
			closeTrades();
			warn("Can't level it up from level " + villagerLevel + ": no trade it gives experience for that you can pay for");
			go(Step.DONE);
			return;
		}
		MerchantOffer o = offers.get(pick);
		int to = slotFor(menu, o.getResult());
		if (to < 0) {
			closeTrades();
			warn("Can't level it up: no room in your inventory");
			go(Step.DONE);
			return;
		}
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 2) || !Myriad.inventory().prepareClick()) return;
		menu.setSelectionHint(pick);
		menu.tryMoveItems(pick);
		mc.getConnection().send(new ServerboundSelectTradePacket(pick));
		mc.gameMode.handleContainerInput(menu.containerId, 2, 0, ContainerInput.PICKUP, mc.player);
		mc.gameMode.handleContainerInput(menu.containerId, to, 0, ContainerInput.PICKUP, mc.player);
		villagerXp += Math.max(1, o.getXp());
		o.increaseUses();
	}

	/**
	 * The trade to level it up with: one you can pay for, not sold out, giving experience; sales of yours first (you get
	 * emeralds), then the most experience for the emeralds. -1 if none.
	 */
	private int levelTrade(MerchantOffers list) {
		int best = -1;
		double bestScore = -1;
		for (int i = 0; i < list.size(); i++) {
			MerchantOffer o = list.get(i);
			if (!affordable(o)) continue;
			boolean sale = o.getResult().is(Items.EMERALD);
			int emeralds = (o.getCostA().is(Items.EMERALD) ? o.getCostA().getCount() : 0) + (o.getCostB().is(Items.EMERALD) ? o.getCostB().getCount() : 0);
			double score = (sale ? 1000 : 0) + Math.max(1, o.getXp()) / (double) Math.max(1, emeralds);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	/** A slot of your inventory in the trading menu to put {@code result} down in: one with room for it, else an empty one; -1 if none. */
	private int slotFor(MerchantMenu menu, ItemStack result) {
		for (Slot s : menu.slots) {
			ItemStack here = s.getItem();
			if (s.container == mc.player.getInventory() && ItemStack.isSameItemSameComponents(here, result)
				&& here.getCount() + result.getCount() <= here.getMaxStackSize()) return s.index;
		}
		return emptySlot(menu);
	}

	/** At Level Up To: whether the villager is a keeper, by the trades it drew (glass from level 3). */
	private void verdict() {
		boolean hasGlass = offers.stream().anyMatch(o -> o.getResult().is(Items.GLASS));
		boolean five = keptLevel >= 5, needGlass = villagerLevel >= 3 && switch (glass.get()) {
			case OFF -> false;
			case WANTED -> !(five && keptPrice <= keptLowest);
			case NOT_FOR_LEVEL_V -> !five;
		};
		String book = String.format(Locale.ROOT, "%s for %d (lowest %d)", kept, keptPrice, keptLowest);
		if (needGlass && !hasGlass) warn("Miss: level " + villagerLevel + " with " + book + ", but it didn't draw glass, and that can't change now");
		else info("Keeper: level " + villagerLevel + " with " + book + (hasGlass ? ", and glass" : ""));
		if (logRolls.get()) info("Its trades: " + summary(offers));
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
		if (e.screen() instanceof MerchantScreen && (step == Step.TRADES || step == Step.LOOK || step == Step.LOCK || step == Step.LEVEL_WAIT)) e.setScreen(null);
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

	/** Whether you're (even partly) in the block the lectern goes back in. */
	private boolean inLecternsWay() {
		return lectern != null && mc.player.getBoundingBox().intersects(new AABB(lectern).inflate(0.02, 0, 0.02));
	}

	/**
	 * Where to step to be clear of the lectern's block: straight out of it the shortest way (you're usually a sliver in,
	 * having stopped short walking back), far enough past that the walking, which stops within 0.15 of a target, still
	 * clears it.
	 */
	private Vec3 awayFromLectern() {
		AABB box = mc.player.getBoundingBox(), cell = new AABB(lectern).inflate(0.02, 0, 0.02);
		Vec3 at = mc.player.position(), mid = cell.getCenter();
		// How far in you are along each axis, out the side you're nearer.
		double inX = at.x < mid.x ? box.maxX - cell.minX : cell.maxX - box.minX;
		double inZ = at.z < mid.z ? box.maxZ - cell.minZ : cell.maxZ - box.minZ;
		if (inX <= inZ) return at.add((at.x < mid.x ? -1 : 1) * (inX + 0.3), 0, 0);
		return at.add(0, 0, (at.z < mid.z ? -1 : 1) * (inZ + 0.3));
	}

	private void placeLectern() {
		if (mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
			placing = false;
			go(Step.HIRED);
			return;
		}
		// Standing in its way (walked back short, or pushed): a step aside first, once.
		if (inLecternsWay() && !placing) {
			placing = true;
			go(Step.BACK);
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
