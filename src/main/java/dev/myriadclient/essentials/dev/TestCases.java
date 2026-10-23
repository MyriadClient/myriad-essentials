package dev.myriadclient.essentials.dev;

import dev.myriadclient.api.build.BlockRules;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The blocks the development tests build: every way a set of tricky blocks can be placed, and what holds each up. */
final class TestCases {
	static final List<Block> BLOCKS = List.<Block>of(
		Blocks.OAK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.OAK_LOG, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.OBSERVER,
		Blocks.PISTON, Blocks.DISPENSER, Blocks.HOPPER, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LEVER, Blocks.STONE_BUTTON,
		Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.CANDLE, Blocks.SEA_PICKLE, Blocks.SNOW, Blocks.LADDER,
		Blocks.LANTERN, Blocks.END_ROD, Blocks.CHEST, Blocks.NOTE_BLOCK, Blocks.BELL, Blocks.OAK_SIGN, Blocks.GRINDSTONE,
		Blocks.DAYLIGHT_DETECTOR, Blocks.POINTED_DRIPSTONE, Blocks.CRAFTER, Blocks.REDSTONE_WIRE
	);

	private TestCases() {
	}

	/** Every combination of the properties a build sets, one state each, for {@link #BLOCKS}. */
	static List<BlockState> all() {
		List<BlockState> out = new ArrayList<>();
		for (Block block : BLOCKS) {
			Set<String> seen = new LinkedHashSet<>();
			for (BlockState s : block.getStateDefinition().getPossibleStates()) {
				if (BlockRules.placedWithNeighbour(s) || !BlockRules.placeable(s)) continue;
				if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)) continue;
				if (s.hasProperty(BlockStateProperties.CHEST_TYPE) && s.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) continue;
				if (s.hasProperty(BlockStateProperties.BELL_ATTACHMENT) && s.getValue(BlockStateProperties.BELL_ATTACHMENT) == BellAttachType.DOUBLE_WALL) continue;
				if (s.hasProperty(BlockStateProperties.NOTE) && s.getValue(BlockStateProperties.NOTE) % 6 != 0) continue;
				if (s.hasProperty(BlockStateProperties.VERTICAL_DIRECTION) && s.hasProperty(BlockStateProperties.SPELEOTHEM_THICKNESS)
					&& s.getValue(BlockStateProperties.SPELEOTHEM_THICKNESS) != net.minecraft.world.level.block.state.properties.SpeleothemThickness.TIP) continue;
				if (seen.add(key(s))) out.add(canonical(s));
			}
		}
		return out;
	}

	/** The properties that decide the case, as text. */
	static String key(BlockState s) {
		StringBuilder b = new StringBuilder();
		for (Property<?> p : s.getProperties()) {
			if (relevant(s, p)) b.append(p.getName()).append('=').append(s.getValue(p)).append(',');
		}
		return b.toString();
	}

	/** Whether {@code p} is one a build sets on {@code s}'s block (a lever's power is; other blocks' comes from redstone). */
	static boolean relevant(BlockState s, Property<?> p) {
		if (p == BlockStateProperties.POWERED) return s.getBlock() == Blocks.LEVER;
		return BlockRules.PLACED.contains(p) || BlockRules.COUNTED.contains(p) || p == BlockStateProperties.SLAB_TYPE || p == BlockStateProperties.DELAY
			|| p == BlockStateProperties.MODE_COMPARATOR || p == BlockStateProperties.NOTE || p == BlockStateProperties.OPEN
			|| p == BlockStateProperties.INVERTED;
	}

	/** {@code s} with every property that doesn't decide the case at the block's default. */
	static BlockState canonical(BlockState s) {
		BlockState out = s.getBlock().defaultBlockState();
		for (Property<?> p : s.getProperties()) if (relevant(s, p)) out = copy(out, s, p);
		return out;
	}

	static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> p) {
		return to.setValue(p, from.getValue(p));
	}

	/** Where the block that holds {@code s} up goes, relative to it; null when the floor does. */
	static Direction support(BlockState s) {
		if (s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
			AttachFace face = s.getValue(BlockStateProperties.ATTACH_FACE);
			if (face == AttachFace.CEILING) return Direction.UP;
			if (face == AttachFace.WALL) return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
			return null;
		}
		if (s.hasProperty(BlockStateProperties.BELL_ATTACHMENT)) {
			BellAttachType a = s.getValue(BlockStateProperties.BELL_ATTACHMENT);
			if (a == BellAttachType.CEILING) return Direction.UP;
			if (a == BellAttachType.SINGLE_WALL) return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
			return null;
		}
		if (s.is(Blocks.WALL_TORCH) || s.is(Blocks.LADDER)) return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
		if (s.hasProperty(BlockStateProperties.HANGING) && s.getValue(BlockStateProperties.HANGING)) return Direction.UP;
		if (s.hasProperty(BlockStateProperties.VERTICAL_DIRECTION) && s.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN) return Direction.UP;
		return null;
	}

}
