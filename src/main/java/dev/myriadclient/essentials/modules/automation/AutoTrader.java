package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobContext;
import dev.myriadclient.api.job.Progress;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.essentials.util.ContainerLinks;
import dev.myriadclient.api.integration.Baritone;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.player.Reach;
import net.minecraft.world.entity.ai.attributes.Attributes;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.ActionSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.essentials.util.NamedChoiceSetting;
import dev.myriadclient.essentials.util.VillagerTrades;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes one trade with every villager of a kind, over and over: what it costs (emeralds, emerald blocks crafted into
 * emeralds, books, or what a villager buys, carrots say, when the trade is Emerald) is taken from the input chests, spent
 * at each villager with the trade until it's sold out or the payment runs out, and what's got goes into the output
 * chests (each in turn, see {@link ContainerLinks}) whenever the inventory fills up, until the inputs have nothing left
 * to pay with (ones that hoppers feed are waited on and looked into again every 30 s instead, until you stop it). What
 * a trade costs is learned from the villagers' offers. A villager sold out is looked at again now and then for its restock. Baritone walks you between the
 * chests and the villagers when they're out of reach (without it, everything has to be in reach).
 * <p>
 * The trades are read and made with their screen hidden; each choice of trade and each buy (a shift-click on the result,
 * which buys again for as long as the payment lasts) counts against core's click budget.
 */
public class AutoTrader extends Module {
	private static final String ANY = "Any";

	private final SettingGroup sgRender = settings.group("Render");
	private final ContainerLinks links = new ContainerLinks(this, sgGeneral, sgRender, "input chest", "output chest",
		"Where the payment comes from (emeralds, emerald blocks, books, carrots: whatever the trade costs), taken from each in turn.",
		"Where what's got goes, each filled in turn.");
	@SuppressWarnings("unused")
	private final ActionSetting forgetTrades = sgGeneral.action("Forget Known Trades", () -> info("Forgot " + VillagerTrades.forgetAll()
		+ " villagers' trades on this server")).description("Trades are remembered per villager once seen (until its level or job changes): this "
		+ "forgets them all here, so each is looked at again.").build();
	private final NamedChoiceSetting profession = sgGeneral.add(new NamedChoiceSetting("Profession", "Villagers of this kind only (Any: whoever "
		+ "sells the trade).", ANY, null, AutoTrader::professions, NamedChoiceSetting.translated(id -> "entity.minecraft.villager." + id.getPath())));
	private final RegistrySetting<Item> trade = sgGeneral.item("Trade", Items.EXPERIENCE_BOTTLE).description("What to get: bought with "
		+ "emeralds, or Emerald to sell villagers what they buy (carrots, paper) for emeralds.").build();
	private final NamedChoiceSetting enchantment = sgGeneral.add(new NamedChoiceSetting("Enchantment", "For enchanted books: only books with "
		+ "this.", ANY, () -> trade.get() == Items.ENCHANTED_BOOK, this::enchantments,
		NamedChoiceSetting.translated(id -> "enchantment." + id.getNamespace() + "." + id.getPath())));
	private final IntSetting range = sgGeneral.integer("Range").defaultValue(24).range(4, 64)
		.description("How far from you villagers are looked for, in blocks.").build();


	private enum Step { START, TO_INPUT, STOCK, CONVERT, PICK, TO_VILLAGER, LOOK, TRADES, TRADE, TO_OUTPUT, DEPOSIT, WAIT }

	private enum Walk { THERE, WALKING, FAILED }

	/** Ticks a villager is left before it's looked at again: sold out (for a restock), out of reach or not answering. */
	private static final int RESTOCK_TICKS = 1200;
	/** Ticks a walk, an open or the trades may take. */
	private static final int WALK_TICKS = 1200, OPEN_TICKS = 40, TRADES_TICKS = 60;
	/** Opens a chest gets before it's left for a wait, and the ticks between them. */
	private static final int OPEN_TRIES = 3, OPEN_RETRY_TICKS = 10;
	private int openTries;
	/** Ticks between looks into the input chest while there's nothing to do (by hand or by hoppers, it may fill up). */
	private static final int WAIT_TICKS = 600;
	/** Inventory slots kept free for what's bought when stocking up. */
	private static final int ROOM = 9;

	private Step step = Step.START;
	private int ticks;
	private boolean walking;
	private @Nullable Villager villager;
	private @Nullable MerchantOffers offers;
	/** The offer being bought, and how many of its result you had before the last buy. */
	private int offer = -1, boughtBefore;
	/** Villagers sold out (or out of reach, or not answering), by entity id: the tick they're looked at again. */
	private final Map<Integer, Integer> soldOut = new HashMap<>();
	private int clock;
	private boolean inputEmpty, learned;
	/** The input or output chest being gone to (by its place in the list), and what this pass over the inputs took. */
	private int chest, tookThisPass;
	/** What's waited for (said once), or null while trading. */
	private @Nullable String waitingFor;
	private Containers.@Nullable View view;
	/** One way to pay for the trade: a first payment and a second (null if none), as much as one trade asks (the most seen). */
	private record Cost(Item a, int countA, @Nullable Item b, int countB) {}
	/**
	 * The ways the trade is paid for, each on its own (a farmer takes wheat or carrots for emeralds): learned from the
	 * offers seen (and remembered ones), emeralds (and books, for books) until then.
	 */
	private final Map<String, Cost> costs = new LinkedHashMap<>();
	/**
	 * Villagers with the trade in stock that what you carry can't pay for, by entity id: left till you stock up with
	 * something new, or (their prices may have dropped) the tick they're looked at again.
	 */
	private final Map<Integer, Integer> cantPay = new HashMap<>();
	/** The offers tried at the villager being traded with that came to nothing (sold out on the server, or unpaid). */
	private final Set<Integer> tried = new HashSet<>();
	/** Whether the input has been stocked from since you turned it on. */
	private boolean stocked;

	public AutoTrader() {
		super(Tags.AUTOMATION, "Auto Trader", "Trades with villagers from chests into chests: buys with emeralds, or sells them carrots, paper and the like.");
	}

	/** Version 2: one input and one output chest became lists (Inputs, Outputs), and Show Chests Show Containers. */
	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion < 2) {
			// A single position reads as a list of one.
			saved.rename("General", "Input Chest", "Inputs");
			saved.rename("General", "Output Chest", "Outputs");
			saved.rename("Render", "Show Chests", "Show Containers");
		}
	}

	/**
	 * The job it runs as ("Trading": the Jobs window shows it, and can pause or cancel it), and the owner of the chests it
	 * opens: core opens a job's containers quietly, no screen shown and none of yours replaced (the UI, a wheel, chat).
	 * Null between worlds; started again in the next.
	 */
	private @Nullable Job<Void> job;
	private @Nullable Object jobOwner;

	/** Starts the job; one cancelled from the Jobs window turns the module off, one ended by leaving the world starts again in the next. */
	private void startJob() {
		if (mc.player == null) return;
		Job<Void> mine = job = Myriad.jobs().start(this, dev.myriadclient.api.job.Step.of("Trading", TradeRun::new)
			.named(() -> waitingFor != null ? waitingFor : "Trading"));
		mine.result().whenComplete((v, t) -> {
			if (job != mine) return;
			job = null;
			stopWork();
			jobOwner = null;
			if (isEnabled() && (t == null || !Why.of(t).code().equals(Why.LEFT_WORLD))) disable();
		});
	}

	/** Its work, one tick at a time, as the job's step; paused, it lets go of what it has open and starts over after. */
	private final class TradeRun implements dev.myriadclient.api.job.Step.Run<Void> {
		@Override
		public void start(JobContext ctx) {
			jobOwner = ctx.job();
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			tradeTick();
			return Progress.running();
		}

		@Override
		public void pause(JobContext ctx) {
			stopWork();
		}

		@Override
		public void resume(JobContext ctx) {
			go(Step.START);
		}

		@Override
		public void cancel(JobContext ctx) {
			stopWork();
		}

		@Override
		public @Nullable String status() {
			return waitingFor;
		}
	}

	/** In a world with it on but no job (the last one ended with the world): a new one. */
	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (job == null) startJob();
	}

	/**
	 * Whether a screen of yours is up that a chest of this one's would replace: your inventory, or a container you opened
	 * (this one's are never shown). Chests wait till it's closed.
	 */
	private boolean yours() {
		return mc.gui.screen() instanceof AbstractContainerScreen<?>;
	}

	@Override
	protected void onEnable() {
		startJob();
		go(Step.START);
		soldOut.clear();
		inputEmpty = false;
		waitingFor = null;
		openTries = 0;
		costs.clear();
		cantPay.clear();
		learned = false;
		stocked = false;
		if (selling()) return;
		addCost(Items.EMERALD, 1, trade.get() == Items.ENCHANTED_BOOK ? Items.BOOK : null, 1);
	}

	/** Whether it's waiting: for the input chest to fill, villagers to restock or room in the output chest. */
	public boolean waiting() {
		return isEnabled() && step == Step.WAIT;
	}

	/** Waits (see WAIT), saying why once. */
	private void idle(String why) {
		if (!why.equals(waitingFor)) info(why);
		waitingFor = why;
		go(Step.WAIT);
	}

	/** Drops the timers in {@code until} that have run out; true if any did. */
	private boolean expire(Map<Integer, Integer> until) {
		return until.values().removeIf(t -> t <= clock);
	}

	/** Whether emeralds are what's got (selling to villagers), not what's paid. */
	private boolean selling() {
		return trade.get() == Items.EMERALD;
	}

	@Override
	protected void onDisable() {
		Job<Void> j = job;
		job = null;
		if (j != null) j.cancel();
		stopWork();
		jobOwner = null;
	}

	/** Lets go of the walk, the chest and the trades (paused, cancelled, off). */
	private void stopWork() {
		if (walking) Baritone.stop();
		walking = false;
		closeView();
		closeTrades();
	}

	private void go(Step next) {
		step = next;
		ticks = 0;
		if (walking) {
			Baritone.stop();
			walking = false;
		}
	}

	/** A pass over the input chests, from the first. */
	private void toInputs() {
		chest = 0;
		tookThisPass = 0;
		go(Step.TO_INPUT);
	}

	/** A pass over the output chests, from the first. */
	private void toOutputs() {
		chest = 0;
		go(Step.TO_OUTPUT);
	}

	/** The chest being gone to in {@code list}, or null if the list has run out (one was taken off meanwhile). */
	private @Nullable BlockPos at(List<BlockPos> list) {
		return chest < list.size() ? list.get(chest) : null;
	}

	// ---- Settings -----------------------------------------------------------------------------------------------

	private static List<String> professions() {
		List<String> out = new ArrayList<>();
		out.add(ANY);
		BuiltInRegistries.VILLAGER_PROFESSION.keySet().forEach(id -> {
			if (!id.getPath().equals("none") && !id.getPath().equals("nitwit")) out.add(id.toString());
		});
		return out;
	}

	private List<String> enchantments() {
		List<String> ids = new ArrayList<>();
		ids.add(ANY);
		if (mc.level != null) mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElementIds()
			.forEach(k -> ids.add(k.identifier().toString()));
		return ids;
	}

	// ---- Inventory ----------------------------------------------------------------------------------------------

	private int count(Item item) {
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
		return n;
	}

	private int emptySlots() {
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) n++;
		return n;
	}

	/** Whether {@code stack} is what's bought (with the enchantment asked for, for books). */
	private boolean bought(ItemStack stack) {
		if (!stack.is(trade.get())) return false;
		if (trade.get() != Items.ENCHANTED_BOOK || enchantment.get().equals(ANY)) return true;
		ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
		for (var e : stored.entrySet()) if (e.getKey().unwrapKey().map(k -> k.identifier().toString().equals(enchantment.get())).orElse(false)) return true;
		return false;
	}

	/** Whether there's room left for what's bought: an empty slot, or a stack of it with space. */
	private boolean room() {
		if (emptySlots() > 0) return true;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (bought(s) && s.getCount() < s.getMaxStackSize()) return true;
		}
		return false;
	}

	private int boughtCount() {
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (bought(inv.getItem(i))) n += inv.getItem(i).getCount();
		return n;
	}

	// ---- Walking ------------------------------------------------------------------------------------------------

	/**
	 * Walks within reach of {@code pos} ({@code there}: whether you're close enough) with Baritone: THERE once you are,
	 * FAILED without Baritone or after a while. Reach is the server's, not yours: Reach (the module) may stretch yours
	 * past what it takes, and a click from there would go unanswered.
	 */
	private Walk reach(BlockPos pos, boolean there) {
		if (there) {
			if (walking) {
				Baritone.stop();
				walking = false;
			}
			return Walk.THERE;
		}
		if (!Baritone.isAvailable() || ticks > WALK_TICKS) return Walk.FAILED;
		if (!walking || !Baritone.isPathing() && ticks % 40 == 0) walking = Baritone.pathNear(pos, 2);
		return Walk.WALKING;
	}

	/**
	 * Walks to a chest, then {@code next}. Out of reach without Baritone stops it (that won't change); a walk that fails
	 * is tried again after a wait.
	 */
	private void toChest(@Nullable BlockPos pos, String name, Step next) {
		// As far as the open reaches (the server's reach), not Reach's.
		Walk w = pos == null ? Walk.FAILED : reach(pos, Reach.canReach(pos, Placement.Options.forServer().range()));
		if (w == Walk.THERE) go(next);
		else if (w == Walk.FAILED) {
			if (pos != null && Baritone.isAvailable()) idle("Couldn't get to the " + name + ": trying again soon");
			else {
				warn("The " + name + " is out of reach, and Baritone isn't there to walk you");
				toggle();
			}
		}
	}

	/** Leaves the villager being gone to alone for a while (out of reach, asleep, trading with someone else). */
	private void skipVillager() {
		closeTrades();
		if (villager != null) soldOut.put(villager.getId(), clock + RESTOCK_TICKS);
		go(Step.PICK);
	}

	// ---- Ticks --------------------------------------------------------------------------------------------------

	/** A tick of work (the job's). */
	private void tradeTick() {
		ticks++;
		clock++;
		switch (step) {
			case START -> start();
			case TO_INPUT -> toChest(at(links.inputs()), "input chest", Step.STOCK);
			case STOCK -> stock();
			case CONVERT -> convert();
			case PICK -> pickVillager();
			case TO_VILLAGER -> {
				if (villager == null || !villager.isAlive()) {
					go(Step.PICK);
					return;
				}
				// Its hitbox within the server's reach for entities (3 blocks in survival), as the server measures a use.
				double r = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
				Walk w = reach(villager.blockPosition(), villager.getBoundingBox().distanceToSqr(Reach.eyes()) <= r * r);
				if (w == Walk.THERE) go(Step.LOOK);
				else if (w == Walk.FAILED) skipVillager();
			}
			case LOOK -> {
				if (ticks == 1) openTrades();
				else if (ticks > TRADES_TICKS) skipVillager();
			}
			case TRADES -> {
				if (offers != null) chooseOffer();
				else if (ticks > TRADES_TICKS) skipVillager();
			}
			case TRADE -> buy();
			case TO_OUTPUT -> toChest(at(links.outputs()), "output chest", Step.DEPOSIT);
			case DEPOSIT -> deposit();
			case WAIT -> {
				// A villager's wait over, or time for another look into the input chest (and the output's room).
				if (ticks % 20 != 0) return;
				boolean due = expire(soldOut) | expire(cantPay);
				if (due || ticks >= WAIT_TICKS) {
					inputEmpty = false;
					go(Step.START);
				}
			}
		}
	}

	/**
	 * What to do next: put away what's got, turn blocks into emeralds, stock up or trade; with nothing to do, wait (for
	 * the input chest to fill, villagers to restock), so it can be left running.
	 */
	private void start() {
		if (links.inputs().isEmpty() || links.outputs().isEmpty()) {
			warn("Add an input and an output chest first");
			toggle();
			return;
		}
		learnKnown();
		List<Villager> sellers = new ArrayList<>();
		for (Villager v : villagersInRange()) if (wanted(v) && !knownWithout(v)) sellers.add(v);
		if (sellers.isEmpty()) {
			// They may be off somewhere (asleep in a bed out of range, say).
			idle("No villager in range has that trade: waiting for one");
			return;
		}
		boolean visit = false;
		for (Villager v : sellers) visit |= worthVisiting(v);
		if (!room() || boughtCount() > 0 && !visit) {
			toOutputs();
			return;
		}
		// Too few emeralds for a trade, and blocks: made into emeralds before anyone's visited.
		if (!selling() && !paid() && count(Items.EMERALD_BLOCK) > 0) {
			go(Step.CONVERT);
			return;
		}
		// Stocked up first; then whoever what you carry may pay.
		if (visit && (stocked || paid() || inputEmpty)) {
			go(Step.PICK);
			return;
		}
		if (!inputEmpty) {
			toInputs();
			return;
		}
		// Nothing more to take and nobody to pay: waits for restocks and for the input chest to fill.
		idle(soldOut.values().stream().anyMatch(t -> t > clock) ? "Villagers are sold out: waiting for them to restock"
			: "Nothing left in the input chests to pay with: waiting for more");
	}

	private List<Villager> villagersInRange() {
		return mc.level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(range.get()));
	}

	/**
	 * Whether {@code v} is worth going to: of the kind, not sold out, and with an offer what you carry pays for, as far
	 * as its remembered trades say; one not seen yet is, while you carry anything to pay with.
	 */
	private boolean worthVisiting(Villager v) {
		if (!wanted(v) || soldOut.getOrDefault(v.getId(), Integer.MIN_VALUE) > clock || cantPay.containsKey(v.getId())) return false;
		VillagerTrades.Known known = VillagerTrades.of(v);
		if (known == null) return carriesPayment();
		for (VillagerTrades.Trade t : known.trades()) {
			Cost c = cost(t);
			if (c != null && affords(c)) return true;
		}
		return false;
	}

	/** Whether you carry anything a trade might be paid with: emeralds to buy, anything but emeralds to sell. */
	private boolean carriesPayment() {
		if (!selling()) return paid();
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (!inv.getItem(i).isEmpty() && !bought(inv.getItem(i))) return true;
		return false;
	}

	/** What remembered trade {@code t} costs, if it gives what's wanted; else null. */
	private @Nullable Cost cost(VillagerTrades.Trade t) {
		String item = BuiltInRegistries.ITEM.getKey(trade.get()).toString();
		String ench = trade.get() == Items.ENCHANTED_BOOK && !enchantment.get().equals(ANY) ? enchantment.get() : null;
		if (!t.gives(item, ench, 1)) return null;
		Item a = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(t.payment())).orElse(null);
		Item b = t.paymentB().isEmpty() ? null : BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(t.paymentB())).orElse(null);
		return a == null ? null : new Cost(a, t.price(), b, t.priceB());
	}

	private void addCost(Item a, int countA, @Nullable Item b, int countB) {
		String key = BuiltInRegistries.ITEM.getKey(a) + "|" + (b == null ? "" : BuiltInRegistries.ITEM.getKey(b));
		costs.merge(key, new Cost(a, countA, b, countB), (x, y) -> new Cost(a, Math.max(x.countA(), y.countA()), b, Math.max(x.countB(), y.countB())));
	}

	/** Whether you carry what {@code c} asks for (emerald blocks not counted: they're made into emeralds first). */
	private boolean affords(Cost c) {
		return count(c.a()) >= c.countA() && (c.b() == null || count(c.b()) >= c.countB());
	}

	/** Whether you carry enough for one trade, paid any of the ways it's paid. */
	private boolean paid() {
		for (Cost c : costs.values()) if (affords(c)) return true;
		return false;
	}

	/** What {@code o} costs, noted as one more way to pay. */
	private void learn(MerchantOffer o) {
		if (!learned) {
			// The defaults give way to what's really asked.
			costs.clear();
			learned = true;
		}
		ItemStack b = o.getCostB();
		addCost(o.getCostA().getItem(), o.getCostA().getCount(), b.isEmpty() ? null : b.getItem(), b.isEmpty() ? 0 : b.getCount());
	}

	/** What remembered villagers in range (VillagerTrades) ask for the trade, noted. */
	private void learnKnown() {
		for (Villager v : villagersInRange()) {
			VillagerTrades.Known known = wanted(v) ? VillagerTrades.of(v) : null;
			if (known == null) continue;
			for (VillagerTrades.Trade t : known.trades()) {
				Cost c = cost(t);
				if (c == null) continue;
				if (!learned) {
					costs.clear();
					learned = true;
				}
				addCost(c.a(), c.countA(), c.b(), c.countB());
			}
		}
	}

	// ---- Chests -------------------------------------------------------------------------------------------------

	/**
	 * Takes what the trade is paid with from the input, leaving room for what's got: to buy, what the trade costs
	 * (emerald blocks for emeralds too), as many trades' worth as fit; to sell, what villagers are known to take (and,
	 * while some haven't been seen, everything else too), shared out over the free slots.
	 */
	private void stock() {
		if (view == null) {
			if (ticks != 1) return;
			// Not while your inventory or a chest of yours is up: it would take its place. Asked again next tick.
			if (yours()) {
				ticks = 0;
				return;
			}
			BlockPos at = at(links.inputs());
			if (at == null) {
				endStock();
				return;
			}
			Myriad.containers().open(jobOwner, at, OPEN_TICKS, Placement.Options.forServer()).whenComplete((v, t) -> {
				if (t != null || v == null) {
					openFailed(true, at);
					return;
				}
				view = v;
				openTries = 0;
				int reserve = selling() ? 1 : ROOM;
				List<Item> order = new ArrayList<>();
				for (Cost c : costs.values()) {
					if (!order.contains(c.a())) order.add(c.a());
					if (c.b() != null && !order.contains(c.b())) order.add(c.b());
				}
				// To sell: what villagers not seen yet may take is anyone's guess, so then everything; else what's known to sell.
				boolean unseen = villagersInRange().stream().anyMatch(vi -> wanted(vi) && VillagerTrades.of(vi) == null);
				if (selling() && (unseen || costs.isEmpty()))
					for (ItemStack st : v.stacks()) if (!st.isEmpty() && !bought(st) && !order.contains(st.getItem())) order.add(st.getItem());
				order.removeIf(i -> v.count(st -> takes(i, st)) == 0);
				Map<Item, Integer> perTrade = new LinkedHashMap<>();
				for (Cost c : costs.values()) {
					perTrade.merge(c.a(), c.countA(), Math::max);
					if (c.b() != null) perTrade.merge(c.b(), c.countB(), Math::max);
				}
				int trades = selling() ? 0 : tradesThatFit(perTrade, Math.max(1, emptySlots() - reserve));
				int[] took = {0};
				CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
				for (int k = 0; k < order.size(); k++) {
					Item item = order.get(k);
					int left = order.size() - k;
					chain = chain.thenCompose(n -> {
						took[0] += n;
						int amount = selling() ? Math.max(1, (emptySlots() - reserve) / left) * item.getDefaultMaxStackSize()
							: Math.max(1, trades * perTrade.getOrDefault(item, 1) - count(item));
						return v.take(st -> takes(item, st), Containers.Transfer.upTo(amount));
					});
				}
				chain.whenComplete((n, t2) -> {
					if (n != null) took[0] += n;
					closeView();
					tookThisPass += took[0];
					// The next input while there's room for more.
					if (chest + 1 < links.inputs().size() && emptySlots() > reserve) {
						chest++;
						go(Step.TO_INPUT);
						return;
					}
					endStock();
				});
			});
		}
	}

	/**
	 * The pass over the inputs is over. Nothing came: they have nothing left to give. Something new: who couldn't be
	 * paid may be now.
	 */
	private void endStock() {
		stocked = true;
		if (tookThisPass == 0) inputEmpty = true;
		else {
			waitingFor = null;
			cantPay.clear();
		}
		go(Step.START);
	}

	/** Whether {@code stack} in the input is taken as {@code item} (emerald blocks as emeralds). */
	private static boolean takes(Item item, ItemStack stack) {
		return stack.is(item) || item == Items.EMERALD && stack.is(Items.EMERALD_BLOCK);
	}

	/** How many trades' payment ({@code perTrade}: each item's most for one) fits in {@code slots} free slots. */
	private static int tradesThatFit(Map<Item, Integer> perTrade, int slots) {
		int n = 1;
		while (n < 64 * 36) {
			int used = 0;
			for (var e : perTrade.entrySet()) used += (int) Math.ceil((double) (n + 1) * e.getValue() / e.getKey().getDefaultMaxStackSize());
			if (used > slots) break;
			n++;
		}
		return n;
	}

	/** Puts everything bought into the output chest. */
	private void deposit() {
		if (view == null) {
			if (ticks != 1) return;
			if (yours()) {
				ticks = 0;
				return;
			}
			BlockPos at = at(links.outputs());
			if (at == null) {
				endDeposit();
				return;
			}
			Myriad.containers().open(jobOwner, at, OPEN_TICKS, Placement.Options.forServer()).whenComplete((v, t) -> {
				if (t != null || v == null) {
					openFailed(false, at);
					return;
				}
				view = v;
				openTries = 0;
				v.put(this::bought, Containers.Transfer.all()).whenComplete((n, t2) -> {
					closeView();
					if (n != null && n > 0) waitingFor = null;
					// What didn't fit goes to the next output.
					if (boughtCount() > 0 && chest + 1 < links.outputs().size()) {
						chest++;
						go(Step.TO_OUTPUT);
						return;
					}
					endDeposit();
				});
			});
		}
	}

	/** The pass over the outputs is over: whatever's left didn't fit, and is waited on till there's room (a hopper may empty them). */
	private void endDeposit() {
		if (boughtCount() > 0) idle(links.outputs().size() == 1 ? "The output chest is full: waiting for room" : "The output chests are full: waiting for room");
		else if (!room()) idle("Your inventory is full: waiting");
		else go(Step.START);
	}

	/**
	 * A chest that didn't open: if it isn't there, the next one (or a wait, if that was the only one); else it's tried
	 * again, twice more soon, then after a wait.
	 */
	private void openFailed(boolean input, BlockPos pos) {
		String name = input ? "input chest" : "output chest";
		if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof Container)) {
			// Not there right now (broken, or a shulker box between swaps): the next one, or a wait if it was the only one.
			List<BlockPos> list = input ? links.inputs() : links.outputs();
			openTries = 0;
			if (list.size() <= 1) {
				idle("The " + name + " isn't there: waiting for it");
				return;
			}
			if (chest + 1 < list.size()) {
				chest++;
				go(input ? Step.TO_INPUT : Step.TO_OUTPUT);
			} else if (input) endStock();
			else endDeposit();
			return;
		}
		if (++openTries < OPEN_TRIES) {
			// The open goes at tick 1 of the step (see stock and deposit).
			ticks = -OPEN_RETRY_TICKS;
			return;
		}
		openTries = 0;
		idle("Couldn't open the " + name + ": trying again soon");
	}

	private void closeView() {
		Containers.View v = view;
		view = null;
		if (v != null && v.isOpen()) v.close();
	}

	/**
	 * Emerald blocks into emeralds in the inventory's grid, as many as fit (nine a block): the recipe book fills the grid
	 * and one shift-click crafts it all.
	 */
	private void convert() {
		if (ticks == 1 && selling()) {
			go(Step.START);
			return;
		}
		if (ticks == 1) {
			RecipeDisplayEntry recipe = emeraldRecipe();
			int fits = emptySlots() * 64 / 9;
			// A whole stack of blocks needs nine slots free; fewer, a block at a time. Each a placement (not counted as a
			// click on 2b2t) and a click.
			boolean all = recipe != null && count(Items.EMERALD_BLOCK) <= fits;
			int times = all ? 1 : Math.min(fits, 12);
			if (recipe == null || fits == 0 || !Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, Math.max(1, times)) || !Myriad.inventory().prepareClick()) {
				if (recipe == null) warn("The recipe for emeralds from a block isn't in your recipe book");
				else if (fits == 0) toOutputs();
				return;
			}
			for (int i = 0; i < times; i++) {
				mc.getConnection().send(new ServerboundPlaceRecipePacket(InventoryMenu.CONTAINER_ID, recipe.id(), all));
				mc.gameMode.handleContainerInput(InventoryMenu.CONTAINER_ID, InventoryMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, mc.player);
			}
		}
		// A round trip for the emeralds to arrive; villagers they couldn't pay before may take them now.
		if (ticks > 2 + Myriad.server().ping() / 50) {
			cantPay.clear();
			go(Step.START);
		}
	}

	private @Nullable RecipeDisplayEntry emeraldRecipe() {
		var context = SlotDisplayContext.fromLevel(mc.level);
		for (RecipeCollection c : mc.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry r : c.getRecipes()) {
				var results = r.resultItems(context);
				if (results.isEmpty() || !results.getFirst().is(Items.EMERALD)) continue;
				var needs = r.craftingRequirements().orElse(List.of());
				if (needs.size() == 1 && needs.getFirst().test(new ItemStack(Items.EMERALD_BLOCK))) return r;
			}
		}
		return null;
	}

	// ---- Villagers ----------------------------------------------------------------------------------------------

	private boolean wanted(Villager v) {
		if (v.isBaby()) return false;
		if (profession.get().equals(ANY)) return !v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath().equals("none")).orElse(true);
		return v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().toString().equals(profession.get())).orElse(false);
	}

	/** The nearest villager worth going to (see worthVisiting); with none, START decides what's next. */
	private void pickVillager() {
		Villager best = null;
		double bestDist = Double.MAX_VALUE;
		for (Villager v : villagersInRange()) {
			if (!worthVisiting(v)) continue;
			double d = v.distanceToSqr(mc.player);
			if (d < bestDist) {
				bestDist = d;
				best = v;
			}
		}
		villager = best;
		go(best != null ? Step.TO_VILLAGER : Step.START);
	}

	/** Looks at the villager, and once the server has the look, opens its trades (their screen stays hidden). */
	private void openTrades() {
		if (mc.player.containerMenu != mc.player.inventoryMenu || mc.gui.screen() != null) {
			go(Step.PICK);
			return;
		}
		Villager v = villager;
		Vec3 at = v.getBoundingBox().getCenter();
		Vec3 d = at.subtract(mc.player.getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance()));
		offers = null;
		tried.clear();
		Myriad.rotations().request(this, yaw, pitch, Rotations.PRIORITY_HIGH, Rotations.Options.INSTANT, () -> {
			if (step != Step.LOOK || !isEnabled() || mc.player == null) return;
			mc.gameMode.interact(mc.player, v, new EntityHitResult(v, at), InteractionHand.MAIN_HAND);
			go(Step.TRADES);
		});
	}

	@Subscribe(packets = ClientboundMerchantOffersPacket.class)
	private void onOffers(PacketEvent.Receive e) {
		if (!(e.packet() instanceof ClientboundMerchantOffersPacket p) || (step != Step.TRADES && step != Step.TRADE)) return;
		offers = p.getOffers();
		if (villager != null) VillagerTrades.record(villager, offers);
	}

	/** Whether {@code v} is known (VillagerTrades) not to sell the trade at all: then it isn't opened. */
	private boolean knownWithout(Villager v) {
		VillagerTrades.Known known = VillagerTrades.of(v);
		if (known == null) return false;
		String item = BuiltInRegistries.ITEM.getKey(trade.get()).toString();
		String ench = trade.get() == Items.ENCHANTED_BOOK && !enchantment.get().equals(ANY) ? enchantment.get() : null;
		for (VillagerTrades.Trade t : known.trades()) if (t.gives(item, ench, 1)) return false;
		return true;
	}

	/**
	 * The trades are in (or one came to nothing): the next offer with what's wanted, in stock and paid for by what you
	 * carry. With none left, the villager waits: for you to stock up, when an offer is there you can't pay; else for its
	 * restock.
	 */
	private void chooseOffer() {
		offer = -1;
		boolean unpaid = false;
		for (int i = 0; i < offers.size(); i++) {
			MerchantOffer o = offers.get(i);
			if (!bought(o.getResult())) continue;
			learn(o);
			if (o.isOutOfStock()) continue;
			// One that came to nothing while paid for is sold out on the server.
			if (!canPay(o)) unpaid = true;
			else if (offer < 0 && !tried.contains(i)) offer = i;
		}
		if (offer < 0) {
			closeTrades();
			if (unpaid) cantPay.put(villager.getId(), clock + RESTOCK_TICKS);
			else soldOut.put(villager.getId(), clock + RESTOCK_TICKS);
			go(Step.START);
			return;
		}
		boughtBefore = -1;
		go(Step.TRADE);
	}

	/**
	 * One trade a round: the offer chosen (its payment moved in) and the result shift-clicked, which trades for as long as
	 * the payment lasts. Next round, if nothing came of it (sold out, or the payment gone), the next offer.
	 */
	private void buy() {
		if (!(mc.player.containerMenu instanceof MerchantMenu menu)) {
			go(Step.START);
			return;
		}
		if (boughtBefore >= 0) {
			// The last trade's answer takes a round trip.
			if (ticks < 2 + Myriad.server().ping() / 50) return;
			if (boughtCount() <= boughtBefore) {
				tried.add(offer);
				chooseOffer();
				return;
			}
			waitingFor = null;
		}
		if (!room()) {
			closeTrades();
			go(Step.TO_OUTPUT);
			return;
		}
		if (!canPay(offers.get(offer))) {
			tried.add(offer);
			chooseOffer();
			return;
		}
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 2) || !Myriad.inventory().prepareClick()) return;
		boughtBefore = boughtCount();
		menu.setSelectionHint(offer);
		menu.tryMoveItems(offer);
		mc.getConnection().send(new ServerboundSelectTradePacket(offer));
		Myriad.limits().count(PacketLimits.Kind.INVENTORY, 1);
		mc.gameMode.handleContainerInput(menu.containerId, 2, 0, ContainerInput.QUICK_MOVE, mc.player);
		ticks = 0;
	}

	/** Whether you carry what {@code o} costs. */
	private boolean canPay(MerchantOffer o) {
		if (count(o.getCostA().getItem()) < o.getCostA().getCount()) return false;
		return o.getCostB().isEmpty() || count(o.getCostB().getItem()) >= o.getCostB().getCount();
	}

	private void closeTrades() {
		if (mc.player != null && mc.player.containerMenu instanceof MerchantMenu) mc.player.closeContainer();
	}

	/**
	 * The trading screen stays hidden while trades are read and made (they're only opened with no screen of yours up).
	 * The chests' show none by themselves: they're the job's.
	 */
	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (e.screen() instanceof MerchantScreen && (step == Step.LOOK || step == Step.TRADES || step == Step.TRADE)) e.setScreen(null);
	}
}
