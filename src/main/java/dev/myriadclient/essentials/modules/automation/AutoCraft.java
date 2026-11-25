package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.JobContext;
import dev.myriadclient.api.job.Progress;
import dev.myriadclient.api.job.Step;
import dev.myriadclient.api.job.Why;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.player.Reach;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.essentials.util.ContainerLinks;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Crafts the listed items, as many as your inventory makes, whenever it can, crafting the ingredients on the way from
 * what you have (logs into planks for chests, gold blocks into ingots and nuggets for golden carrots: {@link CraftPlanner}),
 * only as much of each as the next step needs. Plans whose steps all fit in two by two go in your inventory's own grid;
 * others at a crafting table in reach, opened without its screen.
 * <p>
 * Each step is the recipe book placing the ingredients (as many as fit, or one set at a time for a few) and a shift-click
 * on the result, which crafts the whole grid; with Drop, the last step's result is thrown out instead (a whole-stack throw
 * on a result crafts and throws again for as long as the grid makes it, as Ctrl+Q does). A whole plan goes in one tick:
 * the server takes the steps in order, so what one makes is there for the next. Each step's click counts against core's
 * click budget (2b2t disconnects at about 80 in 4 seconds; the placement doesn't count there) and its placement against
 * Paper's recipe spam limit (20, a tick each to come back), so a very long plan finishes over a few rounds. Only as much is planned as fits
 * your inventory after every step (with Drop, the wanted item needs no room), so nothing ends up on the ground. Only recipes your recipe book has (unlocked ones). An item
 * that came out no further (your inventory full, say) is left alone for a while.
 * <p>
 * With inputs and outputs ({@link ContainerLinks}), it keeps going by itself: once nothing more can be made from what
 * you carry, what's made goes into the outputs, then the next round's ingredients come from the inputs (as much as makes
 * the most that fits your inventory, in the recipe's proportions, taken in whole stacks with what's over kept for the
 * next round, opening only the inputs that have some: see {@link #stock}), till the inputs run out. Inputs that are
 * empty or not there, and outputs that are full or not there, are waited on: a new box at one (a shulker unloader's or
 * loader's next) is seen the tick it's there, and each is looked into again every 5 s anyway (hoppers may fill or empty
 * one with nothing to see). They have to be in reach, as the table does.
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
	private final SettingGroup sgRender = settings.group("Render");
	private final ContainerLinks links = new ContainerLinks(this, sgGeneral, sgRender, "input", "output",
		"Containers the ingredients are taken from (chests, a shulker unloader's), as much as the next round needs, each in turn.",
		"Containers what's crafted goes into (a shulker loader's), each filled in turn.");

	/**
	 * Ticks between looks into the inputs while there's nothing in them; and sooner while one isn't there, or the outputs
	 * are full (a shulker unloader or loader swapping its box).
	 */
	private static final int STORE_WAIT_TICKS = 100;
	/**
	 * While waiting, the block entity at each input (or output) as it was: one put there since (a box where none was, a
	 * new box for a full or empty one) ends the wait the tick it's there, with nothing sent to find out.
	 */
	private final Map<BlockPos, @Nullable BlockEntity> waitingOn = new HashMap<>();
	/** Whether an input or output wasn't there in the last pass over them. */
	private boolean missed;
	/**
	 * Ticks an input or output must have been where it is before it's opened. A shulker unloader (or loader) watches its
	 * box with a comparator, which only sees a change a couple of ticks after it's made: a fresh box emptied (or filled)
	 * the tick it arrived never read as full (or empty), so the next swap never came. One seen to re-arm 8 ticks after
	 * its new box.
	 */
	private static final int SETTLE_TICKS = 10;
	/** The block entity at each input and output, and the tick it was first seen there. */
	private record Arrival(@Nullable BlockEntity box, long tick) {
	}
	private final Map<BlockPos, Arrival> arrivals = new HashMap<>();
	/** Opens waiting for their container to settle ({@link #SETTLE_TICKS}), run once it has. */
	private final Map<BlockPos, Runnable> settling = new HashMap<>();
	/** The input or output open, its screen hidden; null when none is. */
	private Containers.@Nullable View box;
	/** What's waited for (said once), or null. */
	private @Nullable String waitingFor;

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
	private @Nullable CompletableFuture<Boolean> finished;
	/** What the last plan made, and how much of it (dropping: of all items) you had before it. */
	private @Nullable Item lastItem;
	private int lastCount;
	private int wait, skipAge;
	private boolean busy;
	/**
	 * The job it all runs as ("Crafting"; the Jobs window shows it, and can pause or cancel it), and the owner of what it
	 * opens: core opens a job's containers quietly, no screen shown and none of yours replaced (the UI, a wheel, chat).
	 * Null between worlds; started again in the next.
	 */
	private @Nullable Job<Void> job;
	private @Nullable Object jobOwner;
	/** The job paused or ending: nothing more is opened. */
	private boolean paused;

	public AutoCraft() {
		super(Tags.AUTOMATION, "Auto Craft", "Crafts items as many as you can, in your inventory or at a table in reach, from input containers into output ones.");
	}

	@Override
	protected void onEnable() {
		waitingFor = null;
		seen.clear();
		skipped.clear();
		lastItem = null;
		wait = 0;
		startJob();
	}

	/** Starts the job; one cancelled from the Jobs window turns the module off, one ended by leaving the world starts again in the next. */
	private void startJob() {
		if (mc.player == null) return;
		Job<Void> mine = job = Myriad.jobs().start(this, Step.of("Crafting", CraftRun::new).named(() -> waitingFor != null ? waitingFor : "Crafting"));
		paused = false;
		mine.result().whenComplete((v, t) -> {
			if (job != mine) return;
			job = null;
			stopWork();
			jobOwner = null;
			if (isEnabled() && (t == null || !Why.of(t).code().equals(Why.LEFT_WORLD))) disable();
		});
	}

	/** Its work, one tick at a time, as the job's step. */
	private final class CraftRun implements Step.Run<Void> {
		@Override
		public void start(JobContext ctx) {
			jobOwner = ctx.job();
		}

		@Override
		public Progress<Void> tick(JobContext ctx) {
			craftTick();
			return Progress.running();
		}

		@Override
		public void pause(JobContext ctx) {
			paused = true;
			stopWork();
		}

		@Override
		public void resume(JobContext ctx) {
			paused = false;
		}

		@Override
		public void cancel(JobContext ctx) {
			paused = true;
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

	@Override
	protected void onDisable() {
		Job<Void> j = job;
		job = null;
		if (j != null) j.cancel();
		stopWork();
		jobOwner = null;
	}

	/** Lets go of what it has open or under way (paused, cancelled, off). */
	private void stopWork() {
		endTable(false);
		closeOurs();
		busy = false;
		// Opens still waiting finish now, opening nothing (off).
		List<Runnable> waiting = List.copyOf(settling.values());
		settling.clear();
		waiting.forEach(Runnable::run);
		arrivals.clear();
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
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1) || !Myriad.inventory().prepareClick()) return;
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
				// Only the click counts as one (2b2t counts no recipe placements); the placement has its own tokens.
				if (recipeTokens < 1 || !Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1)) {
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

	// ---- Inputs and outputs -------------------------------------------------------------------------------------

	/** Whether {@code stack} is something listed (what goes to the outputs). */
	private boolean made(ItemStack stack) {
		return !stack.isEmpty() && items.get().contains(stack.getItem());
	}

	private int madeCount() {
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) if (made(inv.getItem(i))) n += inv.getItem(i).getCount();
		return n;
	}

	/**
	 * Waits for one of {@code watched} to change (see {@link #waitingOn}), or a while (hoppers may fill or empty one with
	 * nothing to see), saying why once.
	 */
	private void idle(String why, List<BlockPos> watched) {
		if (!why.equals(waitingFor)) info(why);
		waitingFor = why;
		wait = STORE_WAIT_TICKS;
		waitingOn.clear();
		for (BlockPos pos : watched) waitingOn.put(pos, mc.level == null ? null : mc.level.getBlockEntity(pos));
	}

	/** Whether a container is there now at one of {@link #waitingOn} that wasn't when the wait began. */
	private boolean changed() {
		if (mc.level == null) return false;
		for (var e : waitingOn.entrySet()) {
			BlockEntity now = mc.level.getBlockEntity(e.getKey());
			if (now instanceof Container && now != e.getValue()) return true;
		}
		return false;
	}

	/** Back to crafting: what moved is the server's word already (a move ends asking it), so at once. */
	private void moved() {
		waitingFor = null;
		skipped.clear();
		wait = 0;
	}

	/** Closes whatever this has open (an input, an output or the table left open for the next to replace). */
	private void closeOurs() {
		box = null;
		Myriad.containers().current().ifPresent(v -> {
			if (v.opener() == jobOwner && jobOwner != null) v.close();
		});
	}

	/**
	 * Opens the container at {@code pos} (its screen hidden; whatever this had open before is replaced, with no close
	 * first) and runs {@code use} on it, leaving it open for the next step to replace or close; completes with what
	 * {@code use} gave, 0 if it didn't open (said once: likely out of reach).
	 */
	private CompletableFuture<Integer> withBox(BlockPos pos, String name, Function<Containers.View, CompletableFuture<Integer>> use) {
		CompletableFuture<Integer> done = new CompletableFuture<>();
		if (!isEnabled() || job == null || paused) {
			done.complete(0);
			return done;
		}
		// Not there right now (a shulker box between swaps): passed over, looked at again soon.
		if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof Container)) {
			missed = true;
			done.complete(0);
			return done;
		}
		// Just put there (a shulker unloader's next box): left alone a moment, so what watches it sees it as it came. And
		// none opened while you have your inventory or a container open: it would take your screen's place.
		if (!settled(pos) || yours()) {
			settling.put(pos, () -> withBox(pos, name, use).whenComplete((n, t) -> done.complete(n == null ? 0 : n)));
			return done;
		}
		Myriad.containers().open(jobOwner, pos, OPEN_TICKS, Placement.Options.forServer()).whenComplete((v, t) -> {
			if (t != null || v == null || !isEnabled()) {
				if (v != null) v.close();
				String why = "Couldn't open an " + name + ": out of reach, or a shulker box with no room to open?";
				if (!why.equals(waitingFor)) warn(why);
				waitingFor = why;
				done.complete(0);
				return;
			}
			box = v;
			CompletableFuture<Integer> used;
			try {
				used = use.apply(v);
			} catch (RuntimeException e) {
				used = CompletableFuture.failedFuture(e);
			}
			used.whenComplete((n, t2) -> done.complete(n == null ? 0 : n));
		});
		return done;
	}

	/**
	 * Whether the container at {@code pos} has been there {@link #SETTLE_TICKS}: one there when this started counts as
	 * settled, one not yet noted (it came since this tick's look) as just arrived.
	 */
	private boolean settled(BlockPos pos) {
		Arrival a = arrivals.get(pos);
		if (a == null || mc.level == null) return true;
		return a.box() == mc.level.getBlockEntity(pos) && Ticks.since(a.tick()) >= SETTLE_TICKS;
	}

	/** Notes when each input's and output's block entity arrived, and runs the opens that were waiting for it to settle. */
	private void trackArrivals() {
		if (mc.level == null) return;
		List<BlockPos> linked = new ArrayList<>(links.inputs());
		linked.addAll(links.outputs());
		arrivals.keySet().retainAll(linked);
		for (BlockPos pos : linked) {
			BlockEntity now = mc.level.getBlockEntity(pos);
			Arrival a = arrivals.get(pos);
			// The first look isn't an arrival: what's there when it starts has been there a while.
			if (a == null) arrivals.put(pos, new Arrival(now, Long.MIN_VALUE / 2));
			else if (a.box() != now) arrivals.put(pos, new Arrival(now, Ticks.current()));
		}
		if (settling.isEmpty()) return;
		for (BlockPos pos : List.copyOf(settling.keySet())) {
			if ((!settled(pos) || yours()) && isEnabled() && job != null && !paused) continue;
			settling.remove(pos).run();
		}
	}

	/** Puts what's made into the outputs, each in turn while some is left; what none has room for is waited on. */
	private void deposit() {
		busy = true;
		missed = false;
		List<BlockPos> outputs = links.outputs();
		CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
		for (BlockPos pos : outputs) {
			chain = chain.thenCompose(n -> madeCount() == 0 ? CompletableFuture.completedFuture(n)
				: withBox(pos, "output", v -> v.put(this::made, Containers.Transfer.all())).thenApply(m -> n + m));
		}
		chain.whenComplete((n, t) -> {
			if (!isEnabled()) {
				busy = false;
				closeOurs();
				return;
			}
			// All put away: straight on to the inputs, the output's screen replaced by the first.
			if (madeCount() == 0 && t == null && !links.inputs().isEmpty()) {
				stock();
				return;
			}
			busy = false;
			closeOurs();
			// A full one swapped for an empty one (a shulker loader's) is seen the tick it's there; one hoppers empty, in a while.
			if (madeCount() > 0) idle(missed ? "An output isn't there right now: waiting for it"
				: outputs.size() == 1 ? "The output is full: waiting for room" : "The outputs are full: waiting for room", outputs);
			else moved();
		});
	}

	/**
	 * What each input held when it was last open, and the block entity it was then: one put there since (a shulker
	 * unloader's next box) is a new one, so what it holds isn't known.
	 */
	private record Seen(@Nullable BlockEntity box, Map<Item, Integer> items) {
	}
	private final Map<BlockPos, Seen> seen = new HashMap<>();

	/** What the inputs are known to hold between them (ones not known, as nothing). */
	private Map<Item, Integer> known() {
		Map<Item, Integer> all = new HashMap<>();
		for (var e : seen.entrySet()) {
			if (!fresh(e.getKey())) continue;
			e.getValue().items().forEach((item, n) -> all.merge(item, n, Integer::sum));
		}
		return all;
	}

	/** Whether what's known of the input at {@code pos} still stands: seen, and the same box there since. */
	private boolean fresh(BlockPos pos) {
		Seen s = seen.get(pos);
		return s != null && mc.level != null && mc.level.getBlockEntity(pos) == s.box();
	}

	private static Map<Item, Integer> contents(Containers.View v) {
		Map<Item, Integer> items = new HashMap<>();
		for (ItemStack st : v.stacks()) if (!st.isEmpty()) items.merge(st.getItem(), st.getCount(), Integer::sum);
		return items;
	}

	/**
	 * Fetches the next round's ingredients from the inputs, as much of each as makes the most of the first listed item
	 * that fits your inventory, made and all ({@link #fetchList}). Each input is opened only if what it holds isn't
	 * known or it has some of what's still needed (servers drop you for a flood of opens), and what it holds is noted
	 * as it's taken from. Inputs not known yet are looked into first, each once; with nothing to be had from what's
	 * known, all of them are looked into again next time (hoppers may have filled them).
	 */
	private void stock() {
		busy = true;
		missed = false;
		List<BlockPos> inputs = links.inputs();
		seen.keySet().retainAll(inputs);
		CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
		for (BlockPos pos : inputs) {
			chain = chain.thenCompose(n -> {
				if (fresh(pos)) return CompletableFuture.completedFuture(n);
				return withBox(pos, "input", v -> {
					seen.put(pos, new Seen(mc.level.getBlockEntity(pos), contents(v)));
					return CompletableFuture.completedFuture(0);
				}).thenApply(k -> n);
			});
		}
		for (BlockPos pos : inputs) {
			chain = chain.thenCompose(n -> {
				Map<Item, Integer> need = fetchList(known());
				Seen s = seen.get(pos);
				if (need.isEmpty() || !fresh(pos) || s == null || need.keySet().stream().noneMatch(item -> s.items().getOrDefault(item, 0) > 0))
					return CompletableFuture.completedFuture(n);
				return withBox(pos, "input", v -> {
					seen.put(pos, new Seen(mc.level.getBlockEntity(pos), contents(v)));
					// Asked again with what's really there now.
					Map<Item, Integer> want = fetchList(known());
					return takeFrom(v, want).thenApply(k -> {
						if (v.isOpen()) seen.put(pos, new Seen(mc.level.getBlockEntity(pos), contents(v)));
						return k;
					});
				}).thenApply(k -> n + k);
			});
		}
		chain.whenComplete((n, t) -> {
			if (!isEnabled()) {
				busy = false;
				closeOurs();
				return;
			}
			if (t != null || n == null || n <= 0) {
				busy = false;
				closeOurs();
				// Nothing to be had: everything's looked into again next time.
				seen.clear();
				idle(missed ? "An input isn't there right now: waiting for it" : "Nothing in the inputs to craft with: waiting for more", inputs);
				return;
			}
			moved();
			craftNext();
		});
	}

	/**
	 * Takes what's still needed ({@code need}, counted down as it comes) from the open input in whole slots, shift-clicks
	 * only: as many of its stacks as cover it, so up to a stack more than asked (what's over goes into the next round).
	 */
	private CompletableFuture<Integer> takeFrom(Containers.View v, Map<Item, Integer> need) {
		CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
		for (Item item : new ArrayList<>(need.keySet())) {
			chain = chain.thenCompose(m -> {
				int amount = need.getOrDefault(item, 0);
				if (amount <= 0 || !v.isOpen()) return CompletableFuture.completedFuture(m);
				// The container's stacks of it in order (as a take goes), till they cover what's needed.
				int whole = 0;
				for (ItemStack st : v.stacks()) {
					if (whole >= amount) break;
					if (st.is(item)) whole += st.getCount();
				}
				if (whole == 0) return CompletableFuture.completedFuture(m);
				return v.take(st -> st.is(item), Containers.Transfer.upTo(whole)).thenApply(k -> {
					need.put(item, amount - k);
					return m + k;
				});
			});
		}
		return chain;
	}

	/**
	 * What to fetch from storage ({@code stored}, the inputs' items between them) for the next round: for the first
	 * listed item it makes any of, the ingredients of as many as can be crafted with everything (made and all) fitting
	 * your inventory, less what you carry. Empty if nothing's to be made.
	 */
	private Map<Item, Integer> fetchList(Map<Item, Integer> stored) {
		boolean table = tableInReach() != null;
		if (!table && !grid.get()) return Map.of();
		List<RecipeDisplayEntry> recipes = recipes(table);
		Function<RecipeDisplayEntry, List<net.minecraft.world.item.crafting.Ingredient>> ingredients = r -> r.craftingRequirements().orElse(List.of());
		Function<Item, Integer> maxStack = item -> new ItemStack(item).getMaxStackSize();
		CraftPlanner unbounded = new CraftPlanner(recipes, this::result, ingredients, maxStack, Integer.MAX_VALUE, !drop.get());
		IntFunction<CraftPlanner> bounded = slots -> new CraftPlanner(recipes, this::result, ingredients, maxStack, slots, !drop.get());
		Map<Item, Integer> carried = have(), all = new HashMap<>(carried);
		stored.forEach((item, n) -> all.merge(item, n, Integer::sum));
		for (Item wanted : items.get()) {
			CraftPlanner.Plan most = unbounded.best(wanted, all);
			if (most == null) continue;
			RecipeDisplayEntry r = most.last().recipe();
			// The most crafts whose ingredients fit beside what you carry, the crafting after them too.
			int lo = 0, hi = most.crafts();
			Map<Item, Integer> best = null;
			while (lo < hi) {
				int mid = (lo + hi + 1) / 2;
				Map<Item, Integer> fetch = fetchFor(unbounded, bounded, r, mid, all, carried);
				if (fetch != null) {
					lo = mid;
					best = fetch;
				} else {
					hi = mid - 1;
				}
			}
			if (best != null && !best.isEmpty()) return best;
		}
		return Map.of();
	}

	/**
	 * What to fetch to craft {@code r} {@code crafts} times, or null if it (or the crafting after) won't fit your
	 * inventory with a slot to spare for each thing fetched (whole stacks are taken, up to one more than asked).
	 */
	private static @Nullable Map<Item, Integer> fetchFor(CraftPlanner unbounded, IntFunction<CraftPlanner> bounded, RecipeDisplayEntry r, int crafts,
		Map<Item, Integer> all, Map<Item, Integer> carried) {
		Map<Item, Integer> uses = unbounded.uses(r, crafts, all);
		if (uses == null) return null;
		Map<Item, Integer> fetch = new HashMap<>(), after = new HashMap<>(carried);
		for (var e : uses.entrySet()) {
			int more = e.getValue() - carried.getOrDefault(e.getKey(), 0);
			if (more <= 0) continue;
			fetch.put(e.getKey(), more);
			after.merge(e.getKey(), more, Integer::sum);
		}
		int slots = 0;
		for (var e : after.entrySet()) slots += (e.getValue() + e.getKey().getDefaultMaxStackSize() - 1) / e.getKey().getDefaultMaxStackSize();
		int room = 36 - fetch.size();
		if (slots > room) return null;
		return bounded.apply(room).plan(r, crafts, after) != null ? fetch : null;
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
			// Within the server's reach, as the open measures (Reach may stretch yours past it).
			if (!mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE) || !Reach.canReach(p, Placement.Options.forServer().range())) continue;
			double d = Vec3.atCenterOf(p).distanceToSqr(eyes);
			if (d < bestDist) {
				bestDist = d;
				best = p.immutable();
			}
		}
		return best;
	}

	/**
	 * Whether a screen of yours is up that a container of this one's would replace: your inventory, or a container you
	 * opened (this one's are never shown). Your clicks there need it closed on the server, so it is, and this waits.
	 */
	private boolean yours() {
		return mc.gui.screen() instanceof AbstractContainerScreen<?>;
	}

	/** A tick of work (the job's). */
	private void craftTick() {
		trackArrivals();
		recipeTokens = Math.min(RECIPE_BURST, recipeTokens + RECIPE_REFILL);
		if (yours()) {
			// What this had open closed for the server only (your screen stays up), so your clicks work; on again once you close it.
			if (table != null) endTable(false);
			if (box != null) closeOurs();
			return;
		}
		if (++skipAge >= SKIP_TICKS) {
			skipAge = 0;
			skipped.clear();
		}
		if (wait > 0 && !waitingOn.isEmpty() && changed()) wait = 0;
		if (wait > 0) {
			wait--;
			return;
		}
		waitingOn.clear();
		judgeLast();
		if (table != null) {
			tableStep();
			return;
		}
		if (busy) return;
		// Your own inventory, with nothing else open and nothing on the cursor; under the UI, a wheel or chat too (what it
		// opens shows no screen and leaves yours up).
		if (mc.player.containerMenu != mc.player.inventoryMenu || Myriad.containers().current().isPresent()) return;
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
			// Nothing more to make from what you carry: what's made goes out, then the ingredients come in.
			if (!drop.get() && !links.outputs().isEmpty() && madeCount() > 0) deposit();
			else if (!links.inputs().isEmpty()) stock();
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

	/**
	 * After stocking up: straight to the table, the input's screen replaced by it, when what was fetched needs one;
	 * otherwise (the inventory's grid does, or nothing to make) everything's closed and the next tick decides.
	 */
	private void craftNext() {
		box = null;
		BlockPos at = yours() ? null : tableInReach();
		CraftPlanner.Plan small = grid.get() ? plan(false) : null;
		if (at != null && (small == null || !fitsGrid(small)) && plan(true) != null) {
			openTable(at).whenComplete((ok, t) -> {
				busy = false;
				if (t != null || !ok) {
					closeOurs();
					wait = RETRY_TICKS;
				}
			});
			return;
		}
		busy = false;
		closeOurs();
	}

	/** Opens the table at {@code pos} without its screen and crafts what it can; completes once it's closed. */
	private CompletableFuture<Boolean> openTable(BlockPos pos) {
		CompletableFuture<Boolean> done = new CompletableFuture<>();
		Myriad.containers().open(jobOwner, pos, OPEN_TICKS, Placement.Options.forServer()).whenComplete((view, t) -> {
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
			// What's made straight to the outputs, the table's screen replaced by the first (then the inputs, and back).
			if (!drop.get() && !links.outputs().isEmpty() && madeCount() > 0) {
				endTable(true, false);
				deposit();
				return;
			}
			endTable(true, true);
			return;
		}
		run(plan, menu.containerId, view);
	}

	private void endTable(boolean ok) {
		endTable(ok, true);
	}

	/** Done with the table; {@code close}: closed, else left open for the next container to replace. */
	private void endTable(boolean ok, boolean close) {
		Containers.View view = table;
		table = null;
		if (close && view != null && view.isOpen()) view.close();
		CompletableFuture<Boolean> done = finished;
		finished = null;
		if (done != null) done.complete(ok);
	}
}
