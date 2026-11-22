package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Crafts the listed items, as many as your inventory makes, whenever it can, crafting the ingredients on the way from
 * what you have (logs into planks for chests, gold blocks into ingots and nuggets for golden carrots: {@link CraftPlanner}),
 * only as much of each as the next step needs. Plans whose steps all fit in two by two go in your inventory's own grid;
 * others at a crafting table in reach, opened without its screen.
 * <p>
 * Each step is the recipe book placing the ingredients (as many as fit, or one set at a time for a few) and a shift-click
 * on the result, which crafts the whole grid; with Drop, the last step's result is thrown out instead (a whole-stack throw
 * on a result crafts and throws again for as long as the grid makes it, as Ctrl+Q does). A whole plan goes in one tick:
 * the server takes the steps in order, so what one makes is there for the next. Each step's placement and click count
 * against core's click budget (2b2t disconnects at about 80 in 4 seconds) and its placement against Paper's recipe spam
 * limit (20, a tick each to come back), so a very long plan finishes over a few rounds. Only as much is planned as fits
 * your inventory after every step (with Drop, the wanted item needs no room), so nothing ends up on the ground. Only recipes your recipe book has (unlocked ones). An item
 * that came out no further (your inventory full, say) is left alone for a while.
 */
public class AutoCraft extends Module {
	/** Listed items that came out no further last time, left alone for {@link #SKIP_TICKS}. */
	private final Set<Item> skipped = new HashSet<>();
	private final RegistryListSetting<Item> items = sgGeneral.items("Items").description("Items to craft, as many as you can make from what you have "
		+ "(their ingredients crafted on the way).").filter(i -> i != Items.AIR).onChanged(v -> skipped.clear()).build();
	private final BoolSetting grid = sgGeneral.bool("Inventory Grid").defaultValue(true)
		.description("Craft what fits in two by two in your inventory's grid, with no table.").build();
	private final BoolSetting drop = sgGeneral.bool("Drop").defaultValue(false)
		.description("Throw what's crafted out where you look (into hoppers, say) instead of keeping it: the inventory never fills up.").build();

	/** Ticks an item that came out no further is left alone. */
	private static final int SKIP_TICKS = 200;
	/** Ticks the table may take to open, and to wait after it couldn't be. */
	private static final int OPEN_TICKS = 20, RETRY_TICKS = 60;
	/** A step crafted this many times or fewer goes a set at a time (exactly that many); more, as many as fit a batch. */
	private static final int SINGLE_MAX = 16;
	/** Crafts in a full batch: a stack in each grid slot. */
	private static final int BATCH = 64;
	/**
	 * Recipe placements that may go at once, and how many more each tick after: Paper ignores a player's placements past
	 * 20, counting one a tick off (its recipe spam limit), and counts the ones it ignores too, so placing at the rate it
	 * forgives would keep it ignoring them for good once over. Half that rate lets it come back down; a plan longer than
	 * the burst finishes over the next rounds.
	 */
	private static final int RECIPE_BURST = 14;
	private static final double RECIPE_REFILL = 0.5;
	private double recipeTokens = RECIPE_BURST;

	/** The table open for crafting, its screen hidden; null when none is. */
	private Containers.@Nullable View table;
	private @Nullable BlockPos opening;
	private @Nullable CompletableFuture<Boolean> finished;
	/** What the last plan made, and how much of it (dropping: of all items) you had before it. */
	private @Nullable Item lastItem;
	private int lastCount;
	private int wait, skipAge;
	private boolean busy;

	public AutoCraft() {
		super(Tags.AUTOMATION, "Auto Craft", "Crafts items as many as you can, in your inventory or at a table in reach.");
	}

	@Override
	protected void onEnable() {
		skipped.clear();
		lastItem = null;
		wait = 0;
	}

	@Override
	protected void onDisable() {
		endTable(false);
		opening = null;
		busy = false;
	}

	// ---- Recipes and plans --------------------------------------------------------------------------------------

	/** Whether the recipe fits in your inventory's two-by-two grid. */
	private static boolean small(RecipeDisplayEntry r) {
		return switch (r.display()) {
			case ShapedCraftingRecipeDisplay s -> s.width() <= 2 && s.height() <= 2;
			case ShapelessCraftingRecipeDisplay s -> s.ingredients().size() <= 4;
			default -> false;
		};
	}

	/** Your recipe book's crafting recipes ({@code anySize}: three by three ones too). */
	private List<RecipeDisplayEntry> recipes(boolean anySize) {
		List<RecipeDisplayEntry> out = new ArrayList<>();
		for (RecipeCollection c : mc.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry r : c.getRecipes()) {
				boolean crafting = r.display() instanceof ShapedCraftingRecipeDisplay || r.display() instanceof ShapelessCraftingRecipeDisplay;
				if (crafting && r.craftingRequirements().isPresent() && !r.craftingRequirements().get().isEmpty() && (anySize || small(r))) out.add(r);
			}
		}
		return out;
	}

	private @Nullable ItemStack result(RecipeDisplayEntry r) {
		var results = r.resultItems(SlotDisplayContext.fromLevel(mc.level));
		return results.isEmpty() ? null : results.getFirst();
	}

	/** Your inventory, item by item. */
	private Map<Item, Integer> have() {
		Map<Item, Integer> have = new HashMap<>();
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) have.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return have;
	}

	/**
	 * The plan making the most of the first listed item anything can be made of ({@code anySize}: at a table), or null.
	 * {@link #roomBound} says whether it was cut down to what fits your inventory (then the wanted item can't simply be
	 * crafted "as many as fit", which takes all there is).
	 */
	private CraftPlanner.@Nullable Plan plan(boolean anySize) {
		List<RecipeDisplayEntry> recipes = recipes(anySize);
		CraftPlanner planner = new CraftPlanner(recipes, this::result, r -> r.craftingRequirements().orElse(List.of()),
			item -> new ItemStack(item).getMaxStackSize(), 36, !drop.get());
		Map<Item, Integer> have = have();
		for (Item wanted : items.get()) {
			if (skipped.contains(wanted)) continue;
			CraftPlanner.Plan plan = planner.best(wanted, have);
			if (plan == null) continue;
			CraftPlanner unbounded = new CraftPlanner(recipes, this::result, r -> r.craftingRequirements().orElse(List.of()),
				item -> new ItemStack(item).getMaxStackSize(), Integer.MAX_VALUE, !drop.get());
			CraftPlanner.Plan all = unbounded.best(wanted, have);
			roomBound = all != null && all.crafts() > plan.crafts();
			return plan;
		}
		return null;
	}

	/** Whether the last plan made was cut down to what fits your inventory (see {@link #plan}). */
	private boolean roomBound;

	private static boolean fitsGrid(CraftPlanner.Plan plan) {
		for (CraftPlanner.Step s : plan.steps()) if (!small(s.recipe())) return false;
		return true;
	}

	/** How much of {@code item} you have, or (dropping) how many items in all, negated: what a plan's progress is judged by. */
	private int measure(Item item) {
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (drop.get() || inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
		return drop.get() ? -n : n;
	}

	/** The last plan's item, if it came out no further, is left alone for a while. */
	private void judgeLast() {
		if (lastItem != null && measure(lastItem) <= lastCount) skipped.add(lastItem);
		lastItem = null;
	}

	/**
	 * Sends {@code plan} in the menu {@code containerId} (result slot 0) this tick, step by step, as far as the click
	 * budget goes (the rest next round, planned again). Then waits for the server's answers before planning again.
	 */
	private void run(CraftPlanner.Plan plan, int containerId, Containers.@Nullable View view) {
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 2) || !Myriad.inventory().prepareClick()) return;
		ItemStack made = result(plan.last().recipe());
		Item item = made != null ? made.getItem() : null;
		int before = item != null ? measure(item) : 0;
		boolean whole = true;
		send:
		for (CraftPlanner.Step step : plan.steps()) {
			// The wanted item as many as fit (the plan already says that's all it can make), unless room cut it down: then
			// exactly that many, a set at a time (what's left of them next round). Its ingredients exactly too, a set at a
			// time, when that's few enough, so no more is crafted than's needed.
			boolean single = step == plan.last() ? roomBound : step.crafts() <= SINGLE_MAX;
			int times = single ? step.crafts() : (step.crafts() + BATCH - 1) / BATCH;
			boolean throwIt = drop.get() && step == plan.last();
			for (int i = 0; i < times; i++) {
				// The placement and the click both count as clicks (2b2t's proxy counts both).
				if (recipeTokens < 1 || !Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 2)) {
					whole = false;
					break send;
				}
				recipeTokens--;
				mc.getConnection().send(new ServerboundPlaceRecipePacket(containerId, step.recipe().id(), !single));
				ContainerInput input = throwIt ? ContainerInput.THROW : ContainerInput.QUICK_MOVE;
				int button = throwIt ? 1 : 0;
				if (view != null) view.click(CraftingMenu.RESULT_SLOT, button, input);
				else mc.gameMode.handleContainerInput(containerId, InventoryMenu.RESULT_SLOT, button, input, mc.player);
			}
		}
		// Judged next round only if all of it went (one cut short by the budget makes what it can and goes on next round).
		lastItem = whole ? item : null;
		lastCount = before;
		// The answers (what the steps made) come a round trip later; planning before then would plan on the old inventory.
		wait = 2 + Myriad.server().ping() / 50;
	}

	// ---- Ticks --------------------------------------------------------------------------------------------------

	/** The nearest crafting table in reach, or null. */
	private @Nullable BlockPos tableInReach() {
		double range = Reach.blockRange();
		Vec3 eyes = Reach.eyes();
		BlockPos center = BlockPos.containing(eyes);
		int r = (int) Math.ceil(range);
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (!mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE) || !Reach.canReach(p)) continue;
			double d = Vec3.atCenterOf(p).distanceToSqr(eyes);
			if (d < bestDist) {
				bestDist = d;
				best = p.immutable();
			}
		}
		return best;
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		recipeTokens = Math.min(RECIPE_BURST, recipeTokens + RECIPE_REFILL);
		if (++skipAge >= SKIP_TICKS) {
			skipAge = 0;
			skipped.clear();
		}
		if (wait > 0) {
			wait--;
			return;
		}
		judgeLast();
		if (table != null) {
			tableStep();
			return;
		}
		if (busy) return;
		// Your own inventory, with nothing else open and nothing on the cursor.
		if (mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu || Myriad.containers().current().isPresent()) return;
		if (!mc.player.containerMenu.getCarried().isEmpty() || !Myriad.inventory().safeToClick()) return;
		if (grid.get()) {
			CraftPlanner.Plan plan = plan(false);
			if (plan != null && fitsGrid(plan)) {
				run(plan, InventoryMenu.CONTAINER_ID, null);
				return;
			}
		}
		if (plan(true) == null) {
			wait = 10;
			return;
		}
		BlockPos at = tableInReach();
		if (at == null) {
			wait = 10;
			return;
		}
		busy = true;
		openTable(at).whenComplete((ok, t) -> {
			busy = false;
			if (t != null || !ok) wait = RETRY_TICKS;
		});
	}

	/** Opens the table at {@code pos} without its screen and crafts what it can; completes once it's closed. */
	private CompletableFuture<Boolean> openTable(BlockPos pos) {
		opening = pos;
		CompletableFuture<Boolean> done = new CompletableFuture<>();
		Myriad.containers().open(this, pos, OPEN_TICKS, Placement.Options.forServer()).whenComplete((view, t) -> {
			opening = null;
			if (t != null || !isEnabled() || !(view.handler() instanceof CraftingMenu)) {
				if (view != null) view.close();
				done.complete(false);
				return;
			}
			table = view;
			finished = done;
		});
		return done;
	}

	/** A plan a round at the table, until nothing listed can be made; then it closes. */
	private void tableStep() {
		Containers.View view = table;
		if (!view.isOpen() || !(view.handler() instanceof CraftingMenu menu) || mc.player.containerMenu != menu) {
			endTable(false);
			return;
		}
		if (!menu.getCarried().isEmpty()) return;
		CraftPlanner.Plan plan = plan(true);
		if (plan == null) {
			endTable(true);
			return;
		}
		run(plan, menu.containerId, view);
	}

	private void endTable(boolean ok) {
		Containers.View view = table;
		table = null;
		if (view != null && view.isOpen()) view.close();
		CompletableFuture<Boolean> done = finished;
		finished = null;
		if (done != null) done.complete(ok);
	}

	/** The table's screen stays hidden. */
	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (e.screen() instanceof CraftingScreen s && (opening != null || table != null) && mc.player != null && s.getMenu() == mc.player.containerMenu) {
			e.setScreen(null);
		}
	}
}
