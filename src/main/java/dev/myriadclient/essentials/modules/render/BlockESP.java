package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.BoxStyle;
import dev.myriadclient.api.render.HighlightSettings;
import dev.myriadclient.api.render.MergedBoxes;
import dev.myriadclient.api.render.MeshBuilder;
import dev.myriadclient.api.render.Renderer3D;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.world.BlockScan;
import dev.myriadclient.api.world.ChunkCache;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Highlights the blocks you pick (spawners, beds, portals, an ore...) through walls, in each block's own colour or one
 * of your choosing, as boxes or as a shader outline around each block's shape (touching blocks share one, so a vein or
 * a portal is one shape). Chunks are searched with {@link BlockScan}, which skips sections that can't hold a match,
 * when they load and again only when they change; the boxes stay on the GPU.
 */
public class BlockESP extends Module {
	public enum Mode {
		BOXES, OUTLINE
	}

	/** Past this many in one chunk the rest are skipped, so picking something common can't swamp the renderer. */
	private static final int MAX_PER_CHUNK = 2048;

	private final EnumSetting<Mode> mode = sgGeneral.oneOf("Mode", Mode.BOXES).description("Boxes: 3D boxes. Outline: a shader outline around each block's shape.")
		.onChanged(m -> found().setMeshesVisible(m == Mode.BOXES)).build();
	private final RegistryListSetting<Block> blocks = sgGeneral.blocks("Blocks").description("The blocks to highlight.")
		.defaultValue(Blocks.SPAWNER, Blocks.TRIAL_SPAWNER).build();
	private final IntSetting range = sgGeneral.integer("Range").description("Chunks around you.").defaultValue(8).range(1, 32).build();
	private final BoolSetting blockColors = sgGeneral.bool("Block Colors").description("Draw each block in its own colour (as on a map).").defaultValue(true).build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(() -> !blockColors.get()).build();
	private final BoolSetting merge = sgGeneral.bool("Merge").description("Draw touching blocks of the same colour as one outline (portals, veins, beds).")
		.defaultValue(true).visible(() -> mode.get() == Mode.BOXES).build();
	private final BoxStyle style = new BoxStyle(sgGeneral, () -> mode.get() == Mode.BOXES);
	private final HighlightSettings outline = new HighlightSettings(settings.group("Outline"), () -> mode.get() == Mode.OUTLINE);

	/**
	 * Outline mode's colours: each chunk mesh's faces pick theirs from here by index, so one list serves every chunk.
	 * Rebuilt with the meshes; with Block Colors off it's just the one colour, read fresh each frame.
	 */
	private final IntArrayList palette = new IntArrayList();

	private final ChunkCache<List<BlockPos>> found = ChunkCache.of(this, this::scan)
		.range(range::get)
		.mesh(this::mesh)
		.neighbours()
		.build();

	public BlockESP() {
		super(Tags.RENDER, "Blocks", "Highlights the blocks you pick, like spawners or beds, through walls.");
		// A different list needs a new search; anything else only re-meshes.
		settings.onAnyChanged(s -> {
			palette.clear();
			if (s == blocks) found.invalidateAll();
			else found.remeshAll();
		});
	}

	private ChunkCache<List<BlockPos>> found() {
		return found;
	}

	@Override
	protected void onEnable() {
		found.setMeshesVisible(mode.get() == Mode.BOXES);
	}

	@Override
	public String hudInfo() {
		int[] count = {0};
		found.forEach(list -> count[0] += list.size());
		return count[0] == 0 ? null : String.valueOf(count[0]);
	}

	private List<BlockPos> scan(LevelChunk chunk) {
		Set<Block> wanted = blocks.get();
		if (wanted.isEmpty()) return null;
		List<BlockPos> list = new ArrayList<>();
		BlockScan.forEach(chunk, state -> wanted.contains(state.getBlock()), (pos, state) -> {
			if (list.size() < MAX_PER_CHUNK) list.add(pos.immutable());
		});
		return list.isEmpty() ? null : list;
	}

	private void mesh(List<BlockPos> list, MeshBuilder mesh) {
		if (mode.get() == Mode.OUTLINE) {
			// The silhouette only: merged faces, so a solid mass keeps just its outside, each in its palette colour.
			MergedBoxes.draw(mesh, list, lookup, g -> HighlightEvent.Shapes.paletteColor(paletteIndex(g)), g -> 0, true);
			return;
		}
		mesh.lineWidth(style.lineWidth.getFloat());
		if (merge.get()) {
			boolean lines = style.shape.get() != Renderer3D.ShapeMode.FILL, fills = style.shape.get() != Renderer3D.ShapeMode.LINES;
			MergedBoxes.draw(mesh, list, lookup, g -> fills ? style.fill(g, 1) : 0, g -> lines ? style.line(g, 1) : 0, style.throughWalls.get());
			return;
		}
		for (BlockPos pos : list) {
			BlockState state = mc.level.getBlockState(pos);
			style.draw(mesh, shape(state, pos).move(pos), colorOf(state, pos), 1);
		}
	}

	private final MergedBoxes.Lookup lookup = new MergedBoxes.Lookup() {
		@Override
		public int group(BlockPos pos) {
			BlockState state = mc.level.getBlockState(pos);
			return blocks.get().contains(state.getBlock()) ? colorOf(state, pos) : 0;
		}

		@Override
		public AABB shape(BlockPos pos) {
			return BlockESP.shape(mc.level.getBlockState(pos), pos);
		}
	};

	private static AABB shape(BlockState state, BlockPos pos) {
		VoxelShape shape = state.getShape(mc.level, pos);
		return shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
	}

	private int colorOf(BlockState state, BlockPos pos) {
		if (blockColors.get()) {
			// One colour per block, from its default state: some blocks' map colour depends on the state (a bed's head is
			// wool-white, its foot the dye; a log's end differs from its bark), which would split one bed into two shapes.
			MapColor map = state.getBlock().defaultBlockState().getMapColor(mc.level, pos);
			if (map != MapColor.NONE) return 0xFF000000 | map.col;
		}
		return color.argb();
	}

	/** A colour's place in {@link #palette}. With Block Colors off every block uses the one colour, index 0. */
	private int paletteIndex(int color) {
		if (!blockColors.get()) return 0;
		int i = palette.indexOf(color);
		if (i >= 0) return i;
		if (palette.size() >= 256) return 0;
		palette.add(color);
		return palette.size() - 1;
	}

	/** Outline mode: every chunk's mesh, kept on the GPU (chunks off screen are skipped by the renderer). */
	@Subscribe(inGame = true)
	private void onHighlight(HighlightEvent.Shapes e) {
		if (mode.get() != Mode.OUTLINE) return;
		int[] colors = blockColors.get() ? palette.toIntArray() : new int[]{color.argb()};
		if (colors.length > 0) found.highlight(e, outline.style(), colors);
	}

	/** Calls {@code out} with every block being shown and its colour (for Tracers). Nothing while this module is off. */
	public void forEachShown(BiConsumer<BlockPos, Integer> out) {
		if (!isEnabled() || !inGame()) return;
		found.forEach(list -> {
			for (BlockPos pos : list) out.accept(pos, colorOf(mc.level.getBlockState(pos), pos));
		});
	}
}
