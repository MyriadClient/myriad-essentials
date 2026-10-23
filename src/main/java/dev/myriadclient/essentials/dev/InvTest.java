package dev.myriadclient.essentials.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.player.AutoMend;
import dev.myriadclient.essentials.modules.player.AutoSort;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Development only, in singleplayer. {@code .invtest sort [full | <times>]} fills a double chest in front of you with
 * shuffled partial stacks and worn tools (your inventory filled too, three slots left free, or none with {@code full}),
 * opens it with Auto Sort on, and checks on the server that it came out sorted with nothing gained or lost, and that
 * your own items didn't move. {@code .invtest mend} wears and carries damaged Mending gear with bottles in the
 * inventory, turns Auto Mend on, and checks everything is mended and back in place.
 */
public final class InvTest extends Command {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad Essentials");
	private static final int Y = 220;
	private static final Item[] KINDS = {Items.COBBLESTONE, Items.DIRT, Items.OAK_LOG, Items.DIAMOND, Items.ENDER_PEARL, Items.SNOWBALL, Items.TORCH,
		Items.REDSTONE, Items.DIAMOND_SWORD, Items.GOLDEN_APPLE, Items.OBSIDIAN, Items.GLASS, Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL};

	private final Random random = new Random();
	private BlockPos chest;
	private int ticks, clicks;
	private long seed;
	private List<ItemStack> chestBefore, playerBefore;
	private Phase phase = Phase.IDLE;
	/** Runs still to go after this one ({@code .invtest sort <times>}), alternating free slots and a full inventory. */
	private int repeats, passed, run;

	private enum Phase {IDLE, SETUP, OPEN, SORTING, CHECK}

	public InvTest() {
		super("invtest", "Development: Auto Sort and Auto Mend tests.");
	}

	@Override
	public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
		b.then(literal("sort").executes(ctx -> sort(false))
			.then(literal("full").executes(ctx -> sort(true)))
			.then(argument("times", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100)).executes(ctx -> {
				repeats = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "times") - 1;
				passed = 0;
				return sort(false);
			})));
		b.then(literal("mend").executes(ctx -> scenario(mend())));
	}

	// ---- screen modules: set up, open, wait until the module's done, check on the server ---------------------------

	/** A screen module test. {@code done} is asked each tick after opening; {@code verdict} reads the server player. */
	private record Scenario(String name, java.util.function.BiConsumer<ServerLevel, ServerPlayer> setup, Runnable open,
	                        java.util.function.BooleanSupplier done, java.util.function.Function<ServerPlayer, String> verdict) {
	}

	private Scenario scenario;
	private int stableTicks, doneFor;

	private int scenario(Scenario sc) {
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			error("Only in singleplayer.");
			return SINGLE_SUCCESS;
		}
		scenario = sc;
		BlockPos feet = new BlockPos(mc.player.getBlockX(), Y, mc.player.getBlockZ());
		chest = feet.offset(0, 0, 2);
		server(level -> {
			ServerPlayer p = player();
			clearArea(level, feet);
			for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 3, 4))) {
				level.setBlock(pos, pos.getY() == Y - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
			level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.AbstractVillager.class, new net.minecraft.world.phys.AABB(feet).inflate(12))
				.forEach(net.minecraft.world.entity.Entity::discard);
			p.setGameMode(GameType.SURVIVAL);
			p.getInventory().clearContent();
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			p.teleportTo(level, feet.getX() + 0.5, Y, feet.getZ() + 0.5, Set.of(), 0, 30, true);
			sc.setup.accept(level, p);
		});
		ticks = -20;
		phase = Phase.SETUP;
		Myriad.events().subscribe(this);
		return SINGLE_SUCCESS;
	}

	private void scenarioTick() {
		switch (phase) {
			case SETUP -> {
				if (ticks < 0) return;
				phase = Phase.SORTING;
				ticks = 0;
				stableTicks = 0;
				scenario.open.run();
			}
			case SORTING -> {
				if (ticks < 10 || !scenario.done.getAsBoolean() && ticks < 600) {
					doneFor = 0;
					return;
				}
				// The last clicks reach the server a moment later.
				if (++doneFor < 10 && ticks < 600) return;
				phase = Phase.CHECK;
				int took = ticks;
				server(level -> {
					String v = scenario.verdict.apply(player());
					mc.execute(() -> {
						report(scenario.name + ": " + v + " (" + took + " ticks)");
						mc.player.closeContainer();
						stop();
					});
				});
			}
			default -> {
			}
		}
	}

	/** Turns module {@code type} on with its item list {@code id} set to {@code items}. */
	@SuppressWarnings("unchecked")	private static int count(ServerPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	/** Whether nothing the module watches has changed for a second (it's finished or stuck). */
	private boolean settled(java.util.function.IntSupplier state) {
		int now = state.getAsInt();
		if (now != lastState) {
			lastState = now;
			stableTicks = 0;
			return false;
		}
		return ++stableTicks > 20;
	}

	private int lastState = Integer.MIN_VALUE;

	private int inventoryHash() {
		int h = 0;
		for (int i = 0; i < 36; i++) h = h * 31 + ItemStack.hashItemAndComponents(mc.player.getInventory().getItem(i)) * 7 + mc.player.getInventory().getItem(i).getCount();
		return h;
	}	private static ItemStack mending(ServerLevel level, Item item, int damage) {
		ItemStack s = new ItemStack(item);
		s.enchant(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
			.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING), 1);
		s.setDamageValue(damage);
		return s;
	}

	private Scenario mend() {
		return new Scenario("mend", (level, p) -> {
			p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, mending(level, Items.DIAMOND_CHESTPLATE, 300));
			p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			p.getInventory().setItem(3, mending(level, Items.DIAMOND_SWORD, 400));
			p.getInventory().setItem(15, mending(level, Items.DIAMOND_HELMET, 200));
			p.getInventory().setItem(20, new ItemStack(Items.EXPERIENCE_BOTTLE, 64));
			p.getInventory().setItem(21, new ItemStack(Items.EXPERIENCE_BOTTLE, 64));
			p.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
			// Looking up and away: the bottles must still land at your feet.
			p.teleportTo(level, p.getX(), p.getY(), p.getZ(), Set.of(), 90, -40, true);
		}, () -> {
			var m = Modules.get(AutoMend.class);
			if (m.isEnabled()) m.disable();
			m.enable();
		}, () -> !Modules.get(AutoMend.class).isEnabled(), p -> {
			var inv = p.getInventory();
			ItemStack chest = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST), head = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
			boolean placed = chest.is(Items.DIAMOND_CHESTPLATE) && head.is(Items.IRON_HELMET) && inv.getItem(3).is(Items.DIAMOND_SWORD)
				&& inv.getItem(15).is(Items.DIAMOND_HELMET) && inv.getItem(0).is(Items.COBBLESTONE);
			boolean mended = !chest.isDamaged() && !inv.getItem(3).isDamaged() && !inv.getItem(15).isDamaged();
			int used = 128 - count(p, Items.EXPERIENCE_BOTTLE);
			// 900 durability: 900 / 14 = 65 bottles on average.
			return (placed && mended && used < 100 ? "OK" : "WRONG") + " mended " + mended + ", everything back in place " + placed + ", bottles " + used
				+ " (chest " + chest.getDamageValue() + ", sword " + inv.getItem(3).getDamageValue() + ", helmet " + inv.getItem(15).getDamageValue() + ", head " + head + ")";
		});
	}	private static int inBox(ItemStack box, Item item) {
		return dev.myriadclient.api.util.ItemInfo.contents(box).stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
	}	/** The kit: obsidian, crystals, totems, apples, a Sharpness V sword, pearls, and gaps. */
	private static ItemStack[] kitLayout(ServerLevel level) {
		ItemStack[] k = new ItemStack[27];
		java.util.Arrays.fill(k, ItemStack.EMPTY);
		for (int i = 0; i < 10; i++) k[i] = new ItemStack(Items.OBSIDIAN, 64);
		for (int i = 10; i < 16; i++) k[i] = new ItemStack(Items.END_CRYSTAL, 64);
		for (int i = 17; i < 21; i++) k[i] = new ItemStack(Items.TOTEM_OF_UNDYING);
		k[21] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 64);
		k[22] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 32);
		k[24] = sword(level);
		k[26] = new ItemStack(Items.ENDER_PEARL, 16);
		return k;
	}

	private static ItemStack sword(ServerLevel level) {
		ItemStack s = new ItemStack(Items.DIAMOND_SWORD);
		s.enchant(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
			.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 5);
		return s;
	}

	private static void fill(ServerLevel level, BlockPos pos, ItemStack... items) {
		level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
		if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) for (int i = 0; i < items.length; i++) c.setItem(i, items[i]);
	}
	private int sort(boolean full) {
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			error("Only in singleplayer.");
			return SINGLE_SUCCESS;
		}
		seed = random.nextLong();
		Random r = new Random(seed);
		BlockPos feet = new BlockPos(mc.player.getBlockX(), Y, mc.player.getBlockZ());
		chest = feet.offset(0, 0, 2);
		List<ItemStack> contents = new ArrayList<>();
		for (int i = 0; i < 54; i++) contents.add(r.nextInt(5) == 0 ? ItemStack.EMPTY : random(r));
		List<ItemStack> mine = new ArrayList<>();
		for (int i = 0; i < 36; i++) mine.add(!full && (i == 12 || i == 20 || i == 30) ? ItemStack.EMPTY : random(r));
		chestBefore = contents;
		playerBefore = mine;
		server(level -> {
			ServerPlayer p = player();
			clearArea(level, feet);
			for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 3, 4))) {
				level.setBlock(pos, pos.getY() == Y - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
			var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
			level.setBlock(chest, state.setValue(ChestBlock.TYPE, ChestType.LEFT), Block.UPDATE_CLIENTS);
			level.setBlock(chest.east(), state.setValue(ChestBlock.TYPE, ChestType.RIGHT), Block.UPDATE_CLIENTS);
			var container = container(level);
			for (int i = 0; i < 54; i++) container.setItem(i, contents.get(i).copy());
			p.setGameMode(GameType.SURVIVAL);
			p.getInventory().clearContent();
			for (int i = 0; i < 36; i++) p.getInventory().setItem(i, mine.get(i).copy());
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			p.teleportTo(level, feet.getX() + 0.5, Y, feet.getZ() + 0.5, Set.of(), 0, 30, true);
		});
		if (!Modules.get(AutoSort.class).isEnabled()) Modules.get(AutoSort.class).enable();
		ticks = -20;
		clicks = 0;
		phase = Phase.SETUP;
		Myriad.events().subscribe(this);
		return SINGLE_SUCCESS;
	}

	private static ItemStack random(Random r) {
		Item item = KINDS[r.nextInt(KINDS.length)];
		ItemStack s = new ItemStack(item);
		s.setCount(1 + r.nextInt(s.getMaxStackSize()));
		if (s.isDamageableItem()) s.setDamageValue(r.nextInt(3) * 100);
		return s;
	}

	@Subscribe
	private void onSend(PacketEvent.Send e) {
		if (e.packet() instanceof ServerboundContainerClickPacket) clicks++;
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (mc.player == null) {
			stop();
			return;
		}
		ticks++;
		if (scenario != null) {
			scenarioTick();
			return;
		}
		switch (phase) {
			case SETUP -> {
				if (ticks < 0) return;
				phase = Phase.OPEN;
				server(level -> {
					List<String> bad = new ArrayList<>();
					for (int i = 0; i < 36; i++) {
						if (!ItemStack.matches(player().getInventory().getItem(i), playerBefore.get(i))) bad.add(i + ": " + player().getInventory().getItem(i) + " (set " + playerBefore.get(i) + ")");
					}
					if (!bad.isEmpty()) LOG.info("[invtest] setup differs already: {}", bad);
				});
				Myriad.containers().open(chest, 40).whenComplete((v, t) -> {
					if (t != null) {
						report("couldn't open the chest: " + t.getMessage());
						return;
					}
					ticks = 0;
					clicks = 0;
					phase = Phase.SORTING;
				});
			}
			case SORTING -> {
				if (Modules.get(AutoSort.class).busy() && ticks < 1200) return;
				phase = Phase.CHECK;
				int took = ticks, made = clicks;
				server(level -> {
					List<ItemStack> after = new ArrayList<>();
					var container = container(level);
					for (int i = 0; i < 54; i++) after.add(container.getItem(i).copy());
					List<ItemStack> mine = new ArrayList<>();
					for (int i = 0; i < 36; i++) mine.add(player().getInventory().getItem(i).copy());
					mc.execute(() -> check(after, mine, took, made));
				});
			}
			default -> {
			}
		}
	}

	private void check(List<ItemStack> after, List<ItemStack> mine, int took, int made) {
		List<String> problems = new ArrayList<>();
		if (!totals(mine).equals(totals(playerBefore))) problems.add("your totals changed: " + totals(playerBefore) + " -> " + totals(mine));
		if (!totals(after).equals(totals(chestBefore))) problems.add("chest totals changed: " + totals(chestBefore) + " -> " + totals(after));
		for (int i = 0; i < 36; i++) {
			if (!ItemStack.matches(mine.get(i), playerBefore.get(i))) problems.add("your slot " + i + ": " + playerBefore.get(i) + " -> " + mine.get(i));
		}
		boolean gap = false;
		int lastId = -1;
		Map<String, Integer> partials = new HashMap<>();
		for (int i = 0; i < 54; i++) {
			ItemStack s = after.get(i);
			if (s.isEmpty()) {
				gap = true;
				continue;
			}
			if (gap) problems.add("gap before slot " + i);
			int id = BuiltInRegistries.ITEM.getId(s.getItem());
			if (id < lastId) problems.add("out of order at slot " + i + ": " + s);
			lastId = id;
			if (s.getCount() < s.getMaxStackSize()) {
				String key = s.getItem() + "/" + s.getComponents().hashCode();
				if (partials.merge(key, 1, Integer::sum) > 1) problems.add("two part stacks of " + s);
			}
		}
		if (problems.isEmpty()) passed++;
		String line = (problems.isEmpty() ? "sorted" : problems.size() + " problems") + " in " + took + " ticks, " + made + " clicks (seed " + seed + ")";
		if (repeats > 0 || run > 0) LOG.info("[invtest-run] {}", line);
		else report(line);
		for (String p : problems.subList(0, Math.min(12, problems.size()))) LOG.info("[invtest-run]   {}", p);
		stop();
		if (repeats > 0) {
			repeats--;
			run++;
			mc.execute(() -> sort(run % 2 == 1));
		} else if (run > 0) {
			report("sort: " + passed + "/" + (run + 1) + " runs right");
			run = 0;
		}
	}

	/**
	 * Empties containers around {@code feet} and removes loose items, before the blocks are replaced: a container
	 * removed with items in it spills them, and you'd pick them up.
	 */
	static void clearArea(ServerLevel level, BlockPos feet) {
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-6, -2, -6), feet.offset(6, 4, 6))) {
			if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) c.clearContent();
		}
		var box = new net.minecraft.world.phys.AABB(feet).inflate(12);
		for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) item.discard();
		for (var orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box)) orb.discard();
	}

	/** The double chest as the game opens it (its halves in the menu's order). */
	private net.minecraft.world.Container container(ServerLevel level) {
		var state = level.getBlockState(chest);
		return ChestBlock.getContainer((ChestBlock) state.getBlock(), state, level, chest, true);
	}

	private static Map<String, Integer> totals(List<ItemStack> stacks) {
		Map<String, Integer> t = new java.util.TreeMap<>();
		for (ItemStack s : stacks) {
			if (!s.isEmpty()) t.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + (s.isDamageableItem() ? "@" + s.getDamageValue() : ""), s.getCount(), Integer::sum);
		}
		return t;
	}

	private void report(String line) {
		LOG.info("[invtest] {}", line);
		info(line);
	}

	private ServerPlayer player() {
		return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
	}

	private void server(Consumer<ServerLevel> action) {
		MinecraftServer server = mc.getSingleplayerServer();
		var dimension = mc.player.level().dimension();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level != null) action.accept(level);
		});
	}

	private void stop() {
		phase = Phase.IDLE;
		scenario = null;
		Myriad.events().unsubscribe(this);
	}
}
