package dev.myriadclient.essentials.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.build.BlockRules;
import dev.myriadclient.essentials.modules.world.Printer;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Development only: {@code .printtest [lenient|lenient_air|strict|air]} tests the Printer the way it's used. In
 * singleplayer, every test block (see {@link TestCases}) is built on a floating floor, saved with Litematica as a
 * schematic of that area, cleared, and placed back as a Litematica placement; then the Printer module itself builds it
 * while you're moved from spot to spot across it, and every position is compared with the schematic.
 */
public final class PrintTest extends Command {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad Essentials");
	private static final int Y = 200;
	/** Cells per row: 16 x 16 = 256, enough for every case. */
	private static final int ROW = 16;
	/** The area's size along x and z: two blocks a cell, plus the last support. */
	private static final int SIZE = ROW * 2 + 1;
	/** Spots the player stands on, this far apart (on even coordinates, between the cells). */
	private static final int SPOT_SPACING = 6;
	/** Most ticks at one spot, and ticks with nothing left to do there before moving on. */
	private static final int SPOT_TICKS = 300, SPOT_IDLE_TICKS = 20;

	private enum Phase {
		BUILD, SAVE, CLEAR, PRINT, DONE
	}

	private enum Mode {
		LENIENT, LENIENT_AIR, STRICT, AIR
	}

	private Mode mode;
	private Phase phase;
	private BlockPos origin;
	private int ticks, idle, spot;
	private volatile boolean ready;
	private final Map<BlockPos, BlockState> reference = new LinkedHashMap<>();
	private final List<BlockPos> spots = new ArrayList<>();
	private LitematicaSchematic schematic;
	private SchematicPlacement placement;

	public PrintTest() {
		super("printtest", "Development: saves the test blocks as a Litematica schematic and prints it with the Printer.");
	}

	@Override
	public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
		for (Mode m : Mode.values()) b.then(literal(m.name().toLowerCase()).executes(ctx -> start(m)));
		b.then(literal("stop").executes(ctx -> {
			finish("stopped");
			return SINGLE_SUCCESS;
		}));
	}

	private int start(Mode m) {
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			error("Only in singleplayer.");
			return SINGLE_SUCCESS;
		}
		mode = m;
		origin = new BlockPos(mc.player.getBlockX() + 4, Y, mc.player.getBlockZ() + 4);
		reference.clear();
		List<BlockState> cases = TestCases.all();
		for (int i = 0; i < cases.size(); i++) {
			BlockState s = cases.get(i);
			BlockPos cell = origin.offset(1 + 2 * (i % ROW), 0, 1 + 2 * (i / ROW));
			reference.put(cell, s);
			if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) reference.put(cell.above(), s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
			Direction d = TestCases.support(s);
			if (d != null) reference.putIfAbsent(cell.relative(d), Blocks.STONE.defaultBlockState());
		}
		spots.clear();
		for (int x = 0; x <= SIZE; x += SPOT_SPACING) for (int z = 0; z <= SIZE; z += SPOT_SPACING) spots.add(origin.offset(x, 0, z));
		Myriad.events().subscribe(this);
		log("building " + cases.size() + " cases (" + reference.size() + " blocks), " + m.name().toLowerCase());
		server(level -> {
			clearArea(level);
			// No neighbour updates: blocks that need support would drop before it's there.
			reference.forEach((p, s) -> level.setBlock(p, s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
		}, player -> player.teleportTo((ServerLevel) player.level(), origin.getX() + SIZE / 2.0, Y + 6, origin.getZ() + SIZE / 2.0, Set.of(), 0, 90, true));
		phase = Phase.BUILD;
		return SINGLE_SUCCESS;
	}

	/** Air over the whole area and above it, a stone floor under it. */
	private void clearArea(ServerLevel level) {
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(SIZE + 3, 4, SIZE + 3))) {
			level.setBlock(p, p.getY() == Y - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	/** Runs {@code world} and then {@code player} on the server thread; {@link #ready} once both have. */
	private void server(java.util.function.Consumer<ServerLevel> world, java.util.function.Consumer<ServerPlayer> player) {
		ready = false;
		ticks = 0;
		MinecraftServer server = mc.getSingleplayerServer();
		var dimension = mc.player.level().dimension();
		var uuid = mc.player.getUUID();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			if (level == null || p == null) return;
			server.setDifficulty(Difficulty.PEACEFUL, true);
			server.setWeatherParameters(24000, 0, false, false);
			world.accept(level);
			player.accept(p);
			ready = true;
		});
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (mc.player == null) {
			finish("left the world");
			return;
		}
		if (!ready) return;
		ticks++;
		switch (phase) {
			// The client has to have the chunks' new blocks before Litematica reads them.
			case BUILD -> {
				if (ticks >= 40) save();
			}
			case SAVE -> {
				if (ticks >= 20) place();
			}
			case CLEAR -> {
				if (ticks >= 20) {
					phase = Phase.PRINT;
					spot = -1;
					nextSpot();
				}
			}
			case PRINT -> print();
			default -> {
			}
		}
	}

	/** Saves the area as a schematic, the way Litematica's save does, and clears it. */
	private void save() {
		AreaSelection area = new AreaSelection();
		area.setName("printtest");
		area.addSubRegionBox(new Box(origin, origin.offset(SIZE, 2, SIZE), "printtest"), false);
		schematic = LitematicaSchematic.createFromWorld(mc.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false), "Myriad", this::log);
		if (schematic == null) {
			finish("Litematica didn't save the schematic");
			return;
		}
		phase = Phase.SAVE;
		Map<Item, Integer> needed = new LinkedHashMap<>();
		for (BlockState s : reference.values()) {
			if (!BlockRules.placedWithNeighbour(s)) needed.merge(BlockRules.item(s), BlockRules.count(s), Integer::sum);
		}
		server(this::clearArea, player -> {
			player.setGameMode(GameType.SURVIVAL);
			player.getInventory().clearContent();
			needed.forEach((item, count) -> {
				for (int left = count + 4; left > 0; left -= item.getDefaultMaxStackSize()) {
					player.getInventory().add(new ItemStack(item, Math.min(left, item.getDefaultMaxStackSize())));
				}
			});
		});
	}

	/** Puts the schematic back where it came from and turns the Printer on. */
	private void place() {
		placement = SchematicPlacement.createFor(schematic, origin, "printtest", true, true);
		DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
		Printer printer = Modules.get(Printer.class);
		set(printer, "rotate", mode == Mode.STRICT || mode == Mode.AIR);
		set(printer, "air_place", mode == Mode.LENIENT_AIR || mode == Mode.AIR);
		set(printer, "click_settings", true);
		set(printer, "break_wrong_blocks", false);
		if (!printer.isEnabled()) printer.enable();
		phase = Phase.CLEAR;
		ticks = 0;
	}

	@SuppressWarnings("unchecked")
	private static void set(Printer printer, String id, boolean value) {
		((Setting<Boolean>) printer.settings.get(id).orElseThrow()).set(value);
	}

	/** Waits at this spot until nothing in reach is left to do (or long enough), then moves to the next. */
	private void print() {
		Printer printer = Modules.get(Printer.class);
		if (!printer.isEnabled()) {
			finish("the Printer turned itself off");
			return;
		}
		Build build = printer.build();
		// Its job starts the build on its first tick.
		if (build == null) return;
		// Nothing left that's being done or about to be: done, stuck, or out of reach.
		boolean settled = build.steps().stream().noneMatch(s -> s.status() == Build.Status.PLACE || s.status() == Build.Status.BREAK || s.status() == Build.Status.WAITING);
		idle = settled ? idle + 1 : 0;
		if (idle >= SPOT_IDLE_TICKS || ticks >= SPOT_TICKS) nextSpot();
	}

	private void nextSpot() {
		if (++spot >= spots.size()) {
			report();
			return;
		}
		BlockPos at = spots.get(spot);
		idle = 0;
		server(level -> level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS),
			player -> player.teleportTo((ServerLevel) player.level(), at.getX() + 0.5, Y, at.getZ() + 0.5, Set.of(), player.getYRot(), 30, true));
	}

	private void report() {
		phase = Phase.DONE;
		int right = 0, wrong = 0, missing = 0;
		List<String> failures = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : reference.entrySet()) {
			BlockState want = e.getValue(), got = mc.level.getBlockState(e.getKey());
			if (BlockRules.placedWithNeighbour(want)) continue;
			if (got.is(want.getBlock()) && TestCases.key(got).equals(TestCases.key(want))) {
				right++;
			} else if (got.is(want.getBlock())) {
				wrong++;
				failures.add("wrong " + e.getKey().toShortString() + " " + want + " got " + got);
			} else {
				missing++;
				failures.add("missing " + e.getKey().toShortString() + " " + want + " got " + got);
			}
		}
		finish("done: " + right + " right, " + wrong + " wrong, " + missing + " not placed");
		failures.forEach(this::log);
	}

	private void finish(String why) {
		Myriad.events().unsubscribe(this);
		Printer printer = Modules.get(Printer.class);
		if (printer != null && printer.isEnabled()) printer.disable();
		if (placement != null) DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
		placement = null;
		phase = Phase.DONE;
		log(why);
	}

	private void log(String line) {
		LOG.info("[printtest] {}", line);
		info(line);
	}
}
