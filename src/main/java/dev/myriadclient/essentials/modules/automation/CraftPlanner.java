package dev.myriadclient.essentials.modules.automation;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Works out how to craft as many of an item as an inventory allows, crafting the ingredients it lacks from what it has
 * (logs into planks for chests, gold blocks into ingots and nuggets for golden carrots), a few steps deep. Each step is
 * a recipe and how many times to craft it, the ingredients' steps before the one using them, so they can go to the
 * server one after another in a single tick. Only as much of each ingredient is crafted as the steps after it need.
 * <p>
 * A recipe is used only to make what something later needs, never to make an ingredient of itself (gold ingots into
 * blocks and back), and at most {@link #DEPTH} steps down. Everything has to fit the inventory after every step (stacks
 * counted against its slots), so nothing a craft makes is left over to fall on the ground; the wanted item too, unless
 * it's thrown out as it's made.
 */
final class CraftPlanner {
	/** Steps an ingredient may be crafted down from (planks from logs is one). */
	static final int DEPTH = 4;
	/** Crafts a single plan is worked out for at most: a whole inventory's worth. */
	private static final int MOST = 64 * 36;

	/** One step: {@code recipe} crafted {@code crafts} times. */
	record Step(RecipeDisplayEntry recipe, int crafts) {}

	/** A plan: its steps in order, the last making {@code crafts} of the wanted item. */
	record Plan(List<Step> steps, int crafts) {
		Step last() {
			return steps.getLast();
		}
	}

	private final List<RecipeDisplayEntry> recipes;
	private final Function<RecipeDisplayEntry, @Nullable ItemStack> result;
	private final Function<RecipeDisplayEntry, List<Ingredient>> ingredients;
	private final Function<Item, Integer> maxStack;
	private final int slots;
	private final boolean keepResult;

	/**
	 * Plans over {@code recipes} (those the recipe book has, that can be used here), with {@code result} giving what one
	 * craft of a recipe makes (null for none) and {@code ingredients} what it takes, one entry per grid slot.
	 */
	CraftPlanner(List<RecipeDisplayEntry> recipes, Function<RecipeDisplayEntry, @Nullable ItemStack> result,
		Function<RecipeDisplayEntry, List<Ingredient>> ingredients, Function<Item, Integer> maxStack, int slots, boolean keepResult) {
		this.recipes = recipes;
		this.result = result;
		this.ingredients = ingredients;
		this.maxStack = maxStack;
		this.slots = slots;
		this.keepResult = keepResult;
	}

	/** Whether {@code items} fit in the inventory's slots, each item in as few stacks as it takes. */
	private boolean fits(Map<Item, Integer> items) {
		int used = 0;
		for (var e : items.entrySet()) {
			if (e.getValue() <= 0) continue;
			int max = Math.max(1, maxStack.apply(e.getKey()));
			used += (e.getValue() + max - 1) / max;
			if (used > slots) return false;
		}
		return true;
	}

	/** The plan making the most of {@code wanted} from {@code have} (item counts), or null if none makes any. */
	@Nullable Plan best(Item wanted, Map<Item, Integer> have) {
		Plan best = null;
		for (RecipeDisplayEntry r : recipes) {
			ItemStack made = result.apply(r);
			if (made == null || !made.is(wanted)) continue;
			// The most crafts that work out: whether n works never gets better as n grows, so halve the range each time.
			int lo = 0, hi = MOST;
			List<Step> found = null;
			while (lo < hi) {
				int mid = (lo + hi + 1) / 2;
				List<Step> steps = plan(r, mid, have);
				if (steps != null) {
					lo = mid;
					found = steps;
				} else {
					hi = mid - 1;
				}
			}
			if (lo > 0 && found != null && (best == null || lo * made.getCount() > best.crafts() * result.apply(best.last().recipe()).getCount())) {
				best = new Plan(found, lo);
			}
		}
		return best;
	}

	/** The steps crafting {@code r} {@code crafts} times from {@code have} (left as it is), or null if it can't be. */
	@Nullable List<Step> plan(RecipeDisplayEntry r, int crafts, Map<Item, Integer> have) {
		Map<Item, Integer> left = new HashMap<>(have);
		List<Step> steps = new ArrayList<>();
		Set<Item> making = new HashSet<>();
		return craft(r, crafts, left, steps, making, 0) ? steps : null;
	}

	/** Crafts {@code r} {@code crafts} times out of {@code left} (taking from it), its ingredients' steps first. */
	private boolean craft(RecipeDisplayEntry r, int crafts, Map<Item, Integer> left, List<Step> steps, Set<Item> making, int depth) {
		ItemStack made = result.apply(r);
		if (made == null) return false;
		making.add(made.getItem());
		try {
			Map<Ingredient, Integer> uses = new LinkedHashMap<>();
			for (Ingredient i : ingredients.apply(r)) uses.merge(i, 1, Integer::sum);
			for (var e : uses.entrySet()) if (!take(e.getKey(), crafts * e.getValue(), left, steps, making, depth)) return false;
		} finally {
			making.remove(made.getItem());
		}
		steps.add(new Step(r, crafts));
		// The wanted item (the first step down) thrown out as it's made takes no room; anything else must fit.
		if (depth > 0 || keepResult) left.merge(made.getItem(), crafts * made.getCount(), Integer::sum);
		return fits(left);
	}

	/** Takes {@code need} of what {@code ingredient} accepts from {@code left}, crafting what's missing; false if it can't. */
	private boolean take(Ingredient ingredient, int need, Map<Item, Integer> left, List<Step> steps, Set<Item> making, int depth) {
		for (var e : left.entrySet()) {
			if (need <= 0) break;
			if (e.getValue() <= 0 || !ingredient.test(new ItemStack(e.getKey()))) continue;
			int used = Math.min(need, e.getValue());
			e.setValue(e.getValue() - used);
			need -= used;
		}
		if (need <= 0) return true;
		if (depth >= DEPTH) return false;
		// Crafted from something else: the first recipe that works, tried on a copy so a dead end leaves nothing behind.
		for (RecipeDisplayEntry r : recipes) {
			ItemStack made = result.apply(r);
			if (made == null || making.contains(made.getItem()) || !ingredient.test(made)) continue;
			int crafts = (need + made.getCount() - 1) / made.getCount();
			Map<Item, Integer> trial = new HashMap<>(left);
			List<Step> more = new ArrayList<>();
			if (!craft(r, crafts, trial, more, making, depth + 1)) continue;
			// What it made goes to this need; any over stays.
			int extra = crafts * made.getCount() - need;
			trial.merge(made.getItem(), -crafts * made.getCount() + extra, Integer::sum);
			left.clear();
			left.putAll(trial);
			steps.addAll(more);
			return true;
		}
		return false;
	}
}
