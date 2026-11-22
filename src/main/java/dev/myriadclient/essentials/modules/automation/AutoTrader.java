package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.integration.Baritone;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.ActionSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.RegistrySetting;
import dev.myriadclient.api.setting.StringSetting;
import dev.myriadclient.essentials.util.NamedChoiceSetting;
import dev.myriadclient.essentials.util.VillagerTrades;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;

/**
 * Makes one trade with every villager of a kind, over and over: what it costs (emeralds, emerald blocks crafted into
 * emeralds, books, or what a villager buys, carrots say, when the trade is Emerald) is taken from the input chest, spent
 * at each villager with the trade until it's sold out or the payment runs out, and what's got goes into the output chest
 * whenever the inventory fills up, until the input has nothing left to pay with (one that hoppers feed is waited on and
 * looked into again every 30 s instead, until you stop it). What a trade costs is learned from the
 * villagers' offers. A villager sold out is looked at again now and then for its restock. Baritone walks you between the
 * chests and the villagers when they're out of reach (without it, everything has to be in reach).
 * <p>
 * The trades are read and made with their screen hidden; each choice of trade and each buy (a shift-click on the result,
 * which buys again for as long as the payment lasts) counts against core's click budget.
 */
public class AutoTrader extends Module {
	private static final String ANY = "Any";

	private final StringSetting input = sgGeneral.string("Input Chest").description("Where the payment comes from (emeralds, emerald blocks, "
		+ "books, carrots: whatever the trade costs): look at it and press Set Input.").build();
	private final StringSetting output = sgGeneral.string("Output Chest").description("Where what's bought goes: look at it and press Set Output.").build();
	@SuppressWarnings("unused")
	private final ActionSetting setInput = sgGeneral.action("Set Input", () -> pick(true)).description("The container you look at is the input chest.").build();
	@SuppressWarnings("unused")
	private final ActionSetting setOutput = sgGeneral.action("Set Output", () -> pick(false)).description("The container you look at is the output chest.").build();
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

	private enum Step { START, TO_INPUT, STOCK, CONVERT, PICK, TO_VILLAGER, LOOK, TRADES, TRADE, TO_OUTPUT, DEPOSIT, RESTOCK, REFILL }

	/** Ticks a sold-out villager is left before it's looked at again (for a restock). */
	private static final int RESTOCK_TICKS = 1200;
	/** Ticks a walk, an open or the trades may take. */
	private static final int WALK_TICKS = 1200, OPEN_TICKS = 40, TRADES_TICKS = 60;
	/** Ticks between looks into an empty input chest that hoppers feed. */
	private static final int REFILL_TICKS = 600;
	/** Inventory slots kept free for what's bought when stocking up. */
	private static final int ROOM = 9;

	private Step step = Step.START;
	private int ticks;
	private boolean walking;
	private @Nullable Villager villager;
	private @Nullable MerchantOffers offers;
	/** The offer being bought, and how many of its result you had before the last buy. */
	private int offer = -1, boughtBefore;
	/** Villagers sold out (or with nothing to sell), by entity id: the tick they're looked at again. */
	private final Map<Integer, Integer> soldOut = new HashMap<>();
	private int clock;
	private boolean inputEmpty, learned, waitingForInput;
	private Containers.@Nullable View view;
	/**
	 * What the trade costs, item by item, as much as one trade asks for (the most any villager seen asks): learned from the
	 * offers seen (and remembered ones), emeralds (and books, for books) until then.
	 */
	private final Map<Item, Integer> need = new LinkedHashMap<>();

	public AutoTrader() {
		super(Tags.AUTOMATION, "Auto Trader", "Trades with villagers from one chest into another: buys with emeralds, or sells them carrots, paper and the like.");
	}

	@Override
	protected void onEnable() {
		go(Step.START);
		soldOut.clear();
		inputEmpty = false;
		waitingForInput = false;
		need.clear();
		learned = false;
		if (selling()) return;
		need.put(Items.EMERALD, 1);
		if (trade.get() == Items.ENCHANTED_BOOK) need.put(Items.BOOK, 1);
	}

	/** Whether emeralds are what's got (selling to villagers), not what's paid. */
	private boolean selling() {
		return trade.get() == Items.EMERALD;
	}

	@Override
	protected void onDisable() {
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

	/** Makes the container you look at the input (or output) chest. */
	private void pick(boolean isInput) {
		if (mc.player == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
			|| !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof Container)) {
			warn("Look at a chest (or any container) first");
			return;
		}
		BlockPos p = hit.getBlockPos();
		(isInput ? input : output).set(p.getX() + " " + p.getY() + " " + p.getZ());
		info((isInput ? "Input" : "Output") + " chest set");
	}

	private static @Nullable BlockPos parse(String text) {
		String[] parts = text.trim().split("[ ,]+");
		if (parts.length != 3) return null;
		try {
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return null;
		}
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

	/** Walks within reach of {@code pos} with Baritone; true once you're there. False and stops if it can't. */
	private boolean reach(BlockPos pos) {
		if (Reach.canReach(pos)) {
			if (walking) {
				Baritone.stop();
				walking = false;
			}
			return true;
		}
		if (!Baritone.isAvailable()) {
			warn("Out of reach, and Baritone isn't there to walk you");
			toggle();
			return false;
		}
		if (!walking || !Baritone.isPathing() && ticks % 40 == 0) walking = Baritone.pathNear(pos, 2);
		if (ticks > WALK_TICKS) {
			warn("Couldn't get there");
			toggle();
		}
		return false;
	}

	// ---- Ticks --------------------------------------------------------------------------------------------------

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		ticks++;
		clock++;
		switch (step) {
			case START -> start();
			case TO_INPUT -> {
				BlockPos in = parse(input.get());
				if (in != null && reach(in)) go(Step.STOCK);
			}
			case STOCK -> stock();
			case CONVERT -> convert();
			case PICK -> pickVillager();
			case TO_VILLAGER -> {
				if (villager == null || !villager.isAlive()) {
					go(Step.PICK);
					return;
				}
				if (reach(villager.blockPosition())) go(Step.LOOK);
			}
			case LOOK -> {
				if (ticks == 1) openTrades();
				else if (ticks > TRADES_TICKS) go(Step.PICK);
			}
			case TRADES -> {
				if (offers != null) chooseOffer();
				else if (ticks > TRADES_TICKS) {
					closeTrades();
					go(Step.PICK);
				}
			}
			case TRADE -> buy();
			case TO_OUTPUT -> {
				BlockPos out = parse(output.get());
				if (out != null && reach(out)) go(Step.DEPOSIT);
			}
			case DEPOSIT -> deposit();
			case REFILL -> {
				if (ticks >= REFILL_TICKS) {
					inputEmpty = false;
					go(Step.START);
				}
			}
			case RESTOCK -> {
				if (ticks % 20 == 0 && soldOut.values().stream().anyMatch(t -> t <= clock)) go(Step.PICK);
			}
		}
	}

	/** What to do next: put away what's bought, stock up, turn blocks into emeralds, or trade. */
	private void start() {
		if (parse(input.get()) == null || parse(output.get()) == null) {
			warn("Set the input and output chests first");
			toggle();
			return;
		}
		learnKnown();
		// Nothing known of what it costs yet (selling, no villager seen): a villager is looked at first.
		if (need.isEmpty()) {
			go(Step.PICK);
			return;
		}
		if (!room() || boughtCount() > 0 && !paid()) {
			go(Step.TO_OUTPUT);
			return;
		}
		if (!paid()) {
			if (shortOf(Items.EMERALD) && count(Items.EMERALD_BLOCK) > 0) go(Step.CONVERT);
			else if (inputEmpty) {
				// A chest hoppers feed isn't done when it's empty: it's looked into again now and then.
				if (fed()) {
					if (!waitingForInput) info("The input chest is empty: waiting for its hoppers to bring more");
					waitingForInput = true;
					go(Step.REFILL);
					return;
				}
				info("Nothing left in the input chest to pay with: done");
				toggle();
			} else go(Step.TO_INPUT);
			return;
		}
		go(Step.PICK);
	}

	/** Whether there's enough of {@code item} on you for a trade (blocks don't count: they're crafted into emeralds first). */
	private boolean shortOf(Item item) {
		Integer n = need.get(item);
		return n != null && count(item) < n;
	}

	/** Whether you carry enough for a trade: of everything it costs. */
	private boolean paid() {
		for (Item item : need.keySet()) if (shortOf(item)) return false;
		return true;
	}

	/** Whether {@code stack} is something a trade is paid with. */
	private boolean payment(ItemStack stack) {
		return need.containsKey(stack.getItem()) || stack.is(Items.EMERALD_BLOCK) && need.containsKey(Items.EMERALD);
	}

	/** What {@code o} costs, noted. */
	private void learn(MerchantOffer o) {
		if (!learned) {
			// The defaults give way to what's really asked.
			need.clear();
			learned = true;
		}
		need.merge(o.getCostA().getItem(), o.getCostA().getCount(), Math::max);
		if (!o.getCostB().isEmpty()) need.merge(o.getCostB().getItem(), o.getCostB().getCount(), Math::max);
	}

	/** What remembered villagers in range (VillagerTrades) ask for the trade, noted. */
	private void learnKnown() {
		String item = BuiltInRegistries.ITEM.getKey(trade.get()).toString();
		String ench = trade.get() == Items.ENCHANTED_BOOK && !enchantment.get().equals(ANY) ? enchantment.get() : null;
		for (Villager v : mc.level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(range.get()))) {
			VillagerTrades.Known known = wanted(v) ? VillagerTrades.of(v) : null;
			if (known == null) continue;
			for (VillagerTrades.Trade t : known.trades()) {
				if (!t.gives(item, ench, 1)) continue;
				if (!learned) {
					need.clear();
					learned = true;
				}
				BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(t.payment())).ifPresent(i -> need.merge(i, t.price(), Math::max));
				if (!t.paymentB().isEmpty()) BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(t.paymentB())).ifPresent(i -> need.merge(i, t.priceB(), Math::max));
			}
		}
	}

	// ---- Chests -------------------------------------------------------------------------------------------------

	/**
	 * Takes what the trade costs from the input (emerald blocks for emeralds too), as many trades' worth as fit while
	 * leaving room for what's got.
	 */
	private void stock() {
		if (view == null) {
			if (ticks > 1) return;
			Myriad.containers().open(this, parse(input.get()), OPEN_TICKS, Placement.Options.forServer()).whenComplete((v, t) -> {
				if (t != null || v == null) {
					warn("Couldn't open the input chest");
					toggle();
					return;
				}
				view = v;
				int trades = tradesThatFit(Math.max(1, emptySlots() - (selling() ? 1 : ROOM)));
				CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
				for (var e : need.entrySet()) {
					Item item = e.getKey();
					int amount = Math.max(1, trades * e.getValue() - count(item));
					chain = chain.thenCompose(n -> v.take(s -> s.is(item) || item == Items.EMERALD && s.is(Items.EMERALD_BLOCK), Containers.Transfer.upTo(amount)));
				}
				chain.whenComplete((n, t2) -> {
					closeView();
					// Still not enough to pay for one (blocks counted): there's no more.
					boolean enough = true;
					for (var e : need.entrySet()) {
						int have = count(e.getKey()) + (e.getKey() == Items.EMERALD ? count(Items.EMERALD_BLOCK) * 9 : 0);
						if (have < e.getValue()) enough = false;
					}
					if (!enough) inputEmpty = true;
					else waitingForInput = false;
					go(Step.START);
				});
			});
		}
	}

	/** Whether a hopper feeds the input chest (either half of a double): then it may fill up again once empty. */
	private boolean fed() {
		BlockPos in = parse(input.get());
		if (in == null) return false;
		List<BlockPos> halves = new ArrayList<>(List.of(in));
		BlockState state = mc.level.getBlockState(in);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
			halves.add(in.relative(ChestBlock.getConnectedDirection(state)));
		for (BlockPos half : halves) {
			for (Direction d : Direction.values()) {
				BlockPos next = half.relative(d);
				BlockState hopper = mc.level.getBlockState(next);
				if (hopper.getBlock() instanceof HopperBlock && next.relative(hopper.getValue(HopperBlock.FACING)).equals(half)) return true;
			}
		}
		return false;
	}

	/** How many trades' payment fits in {@code slots} free slots. */
	private int tradesThatFit(int slots) {
		int n = 1;
		while (n < 64 * 36) {
			int used = 0;
			for (var e : need.entrySet()) used += (int) Math.ceil((double) (n + 1) * e.getValue() / e.getKey().getDefaultMaxStackSize());
			if (used > slots) break;
			n++;
		}
		return n;
	}

	/** Puts everything bought into the output chest. */
	private void deposit() {
		if (view == null) {
			if (ticks > 1) return;
			Myriad.containers().open(this, parse(output.get()), OPEN_TICKS, Placement.Options.forServer()).whenComplete((v, t) -> {
				if (t != null || v == null) {
					warn("Couldn't open the output chest");
					toggle();
					return;
				}
				view = v;
				v.put(this::bought, Containers.Transfer.all()).whenComplete((n, t2) -> {
					closeView();
					if (!room() && boughtCount() > 0) {
						warn("The output chest is full");
						toggle();
						return;
					}
					go(Step.START);
				});
			});
		}
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
		if (ticks == 1 && !need.containsKey(Items.EMERALD)) {
			go(Step.START);
			return;
		}
		if (ticks == 1) {
			RecipeDisplayEntry recipe = emeraldRecipe();
			int fits = emptySlots() * 64 / 9;
			if (recipe == null || fits == 0 || !Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 2) || !Myriad.inventory().prepareClick()) {
				if (recipe == null) warn("The recipe for emeralds from a block isn't in your recipe book");
				else if (fits == 0) go(Step.TO_OUTPUT);
				return;
			}
			// A whole stack of blocks needs nine slots free; fewer, a block at a time.
			boolean all = count(Items.EMERALD_BLOCK) <= fits;
			int times = all ? 1 : Math.min(fits, 12);
			for (int i = 0; i < times; i++) {
				mc.getConnection().send(new ServerboundPlaceRecipePacket(InventoryMenu.CONTAINER_ID, recipe.id(), all));
				mc.gameMode.handleContainerInput(InventoryMenu.CONTAINER_ID, InventoryMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, mc.player);
			}
		}
		// A round trip for the emeralds to arrive.
		if (ticks > 2 + Myriad.server().ping() / 50) go(Step.START);
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

	/** The nearest villager of the kind not known to be sold out; else waits for a restock. */
	private void pickVillager() {
		Villager best = null;
		double bestDist = Double.MAX_VALUE;
		for (Villager v : mc.level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(range.get()))) {
			if (!wanted(v) || soldOut.getOrDefault(v.getId(), Integer.MIN_VALUE) > clock || knownWithout(v)) continue;
			double d = v.distanceToSqr(mc.player);
			if (d < bestDist) {
				bestDist = d;
				best = v;
			}
		}
		if (best != null) {
			villager = best;
			go(Step.TO_VILLAGER);
			return;
		}
		if (soldOut.isEmpty()) {
			warn("No villager in range sells that");
			toggle();
			return;
		}
		// Everyone's sold out: what's bought goes away meanwhile, then the restock is waited for.
		if (boughtCount() > 0) go(Step.TO_OUTPUT);
		else go(Step.RESTOCK);
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

	/** The trades are in: the one asked for, if it's there and in stock; else this villager is left for now. */
	private void chooseOffer() {
		offer = -1;
		for (int i = 0; i < offers.size(); i++) {
			MerchantOffer o = offers.get(i);
			if (bought(o.getResult())) learn(o);
			if (offer < 0 && bought(o.getResult()) && !o.isOutOfStock()) offer = i;
		}
		if (offer < 0) {
			closeTrades();
			soldOut.put(villager.getId(), clock + RESTOCK_TICKS);
			go(Step.PICK);
			return;
		}
		boughtBefore = -1;
		go(Step.TRADE);
	}

	/**
	 * One buy a round: the trade chosen (its payment moved in) and the result shift-clicked, which buys for as long as
	 * the payment lasts. Next round, if nothing came of it (sold out, or out of emeralds), this villager is done for now.
	 */
	private void buy() {
		if (!(mc.player.containerMenu instanceof MerchantMenu menu)) {
			go(Step.PICK);
			return;
		}
		if (boughtBefore >= 0) {
			// The last buy's answer takes a round trip.
			if (ticks < 2 + Myriad.server().ping() / 50) return;
			if (boughtCount() <= boughtBefore) {
				closeTrades();
				if (canPay(offers.get(offer))) soldOut.put(villager.getId(), clock + RESTOCK_TICKS);
				go(Step.START);
				return;
			}
		}
		if (!room()) {
			closeTrades();
			go(Step.TO_OUTPUT);
			return;
		}
		if (!canPay(offers.get(offer))) {
			closeTrades();
			go(Step.START);
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

	/** The trading screen stays hidden while trades are read and made. */
	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (e.screen() instanceof MerchantScreen && (step == Step.LOOK || step == Step.TRADES || step == Step.TRADE)) e.setScreen(null);
	}
}
