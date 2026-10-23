package dev.myriadclient.essentials.litematica;

import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Target;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.malilib.util.position.LayerRange;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The schematics placed in Litematica, as a {@link Blueprint}: what its schematic world holds inside the enabled
 * placements' boxes, limited to the layers Litematica is showing, the way it's drawn, each block as an exact target
 * (placed as it faces, as many, in its setting). Air inside a box counts (it means "empty here"), so a build that
 * breaks wrong blocks clears it; outside every box nothing is asked for.
 * <p>
 * Only touch this class when Litematica is installed. {@link #forEach} only sees the schematic chunks Litematica has
 * loaded (those near you).
 */
public final class LitematicaBlueprint implements Blueprint {
	private final BooleanSupplier includeAir;
	private final Predicate<BlockState> skip;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final LongOpenHashSet seen = new LongOpenHashSet();

	/** {@code includeAir}: also ask for the air inside the schematic (to clear what's in the way); false leaves it be. */
	public LitematicaBlueprint(BooleanSupplier includeAir) {
		this(includeAir, s -> false);
	}

	/** As {@link #LitematicaBlueprint(BooleanSupplier)}; positions whose schematic block {@code skip} accepts are left out. */
	public LitematicaBlueprint(BooleanSupplier includeAir, Predicate<BlockState> skip) {
		this.includeAir = includeAir;
		this.skip = skip;
	}

	/** Whether Litematica and a schematic world are there to read. */
	public static boolean ready() {
		return SchematicWorldHandler.getSchematicWorld() != null;
	}

	@Override
	public void near(Vec3 center, double range, BiConsumer<BlockPos, Target> action) {
		WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
		if (world == null) return;
		SchematicPlacementManager placements = DataManager.getSchematicPlacementManager();
		LayerRange layers = DataManager.getRenderLayerRange();
		double r2 = range * range;
		int minX = Mth.floor(center.x - range), maxX = Mth.floor(center.x + range);
		int minY = Mth.floor(center.y - range), maxY = Mth.floor(center.y + range);
		int minZ = Mth.floor(center.z - range), maxZ = Mth.floor(center.z + range);
		for (int cx = SectionPos.blockToSectionCoord(minX); cx <= SectionPos.blockToSectionCoord(maxX); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(minZ); cz <= SectionPos.blockToSectionCoord(maxZ); cz++) {
				if (!world.getChunkSource().hasChunk(cx, cz)) continue;
				List<SchematicPlacementManager.PlacementPart> parts = placements.getPlacementPartsInChunk(cx, cz);
				if (parts.isEmpty()) continue;
				// Overlapping boxes would give a position twice; only then are positions remembered.
				boolean overlap = parts.size() > 1, air = includeAir.getAsBoolean();
				if (overlap) seen.clear();
				ChunkAccess chunk = world.getChunk(cx, cz);
				for (SchematicPlacementManager.PlacementPart part : parts) {
					if (!part.getPlacement().isEnabled()) continue;
					IntBoundingBox box = part.getBox();
					int x0 = Math.max(minX, Math.max(box.minX(), cx << 4)), x1 = Math.min(maxX, Math.min(box.maxX(), (cx << 4) + 15));
					int z0 = Math.max(minZ, Math.max(box.minZ(), cz << 4)), z1 = Math.min(maxZ, Math.min(box.maxZ(), (cz << 4) + 15));
					int y0 = Math.max(minY, box.minY()), y1 = Math.min(maxY, box.maxY());
					for (int y = y0; y <= y1; y++) {
						double dy = y + 0.5 - center.y;
						for (int x = x0; x <= x1; x++) {
							double dx = x + 0.5 - center.x;
							for (int z = z0; z <= z1; z++) {
								double dz = z + 0.5 - center.z;
								if (dx * dx + dy * dy + dz * dz > r2 || !layers.isPositionWithinRange(x, y, z)) continue;
								pos.set(x, y, z);
								BlockState state = chunk.getBlockState(pos);
								if (state.isAir() ? !air : skip.test(state)) continue;
								if (overlap && !seen.add(pos.asLong())) continue;
								action.accept(pos, target(state));
							}
						}
					}
				}
			}
		}
	}

	@Override
	public void forEach(BiConsumer<BlockPos, Target> action) {
		WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
		if (world == null) return;
		SchematicPlacementManager placements = DataManager.getSchematicPlacementManager();
		LayerRange layers = DataManager.getRenderLayerRange();
		Set<BlockPos> seen = new HashSet<>();
		for (var placement : placements.getAllSchematicsPlacements()) {
			if (!placement.isEnabled()) continue;
			var box = placement.getEclosingBox();
			if (box == null || box.getPos1() == null || box.getPos2() == null) continue;
			BlockPos min = BlockPos.min(box.getPos1(), box.getPos2()), max = BlockPos.max(box.getPos1(), box.getPos2());
			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				if (!layers.isPositionWithinRange(pos)) continue;
				if (!world.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) continue;
				BlockState state = world.getBlockState(pos);
				if (state.isAir() ? !includeAir.getAsBoolean() : skip.test(state)) continue;
				BlockPos p = pos.immutable();
				if (seen.add(p)) action.accept(p, target(state));
			}
		}
	}

	private static Target target(BlockState state) {
		return state.isAir() ? Target.air() : Target.exact(state);
	}
}
