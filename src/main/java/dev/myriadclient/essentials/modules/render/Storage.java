package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PreviewEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Entities;
import dev.myriadclient.api.world.ChunkCache;
import dev.myriadclient.api.setting.SavedSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.boat.ChestRaft;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Highlights storage: chests, trapped and ender chests, barrels, shulker boxes, hoppers, dispensers and droppers,
 * furnaces and other containers, plus storage you can't place (chest and hopper minecarts, chest boats and rafts,
 * donkeys, mules and llamas carrying chests). Each kind can be turned off and has its own colour; double chests are one
 * box, and shulker boxes can take their dye colour. Drawn in the render mode (the Render window): in Shader mode an
 * outline around each container (touching ones of a colour share it) and around storage entities' exact shape; in Box
 * mode a box around each.
 * <p>
 * Blocks are found per chunk with a {@link ChunkCache} (scanned when a chunk loads and again only when a block in it
 * changes) and their boxes stay on the GPU, so settings only re-mesh; only entities are handed over each frame. Tracers
 * draws lines to everything this shows (its Storage option).
 */
public class Storage extends Module {
	private final IntSetting range = sgGeneral.integer("Range").description("Chunks around you.").defaultValue(8).range(1, 32).build();

	private final SettingGroup sgBlocks = settings.group("Blocks");
	private final Kind chests = kind(sgBlocks, "Chests", true, SettingColor.Mode.YELLOW);
	private final Kind trappedChests = kind(sgBlocks, "Trapped Chests", true, SettingColor.Mode.RED);
	private final Kind enderChests = kind(sgBlocks, "Ender Chests", true, SettingColor.Mode.MAGENTA);
	private final Kind barrels = kind(sgBlocks, "Barrels", true, SettingColor.Mode.YELLOW);
	private final Kind shulkers = kind(sgBlocks, "Shulker Boxes", true, SettingColor.Mode.ACCENT);
	private final BoolSetting dyedShulkers = sgBlocks.bool("Dyed Shulkers").description("Draw shulker boxes in their own dye colour.")
		.defaultValue(true).visible(shulkers.enabled::get).build();
	private final Kind hoppers = kind(sgBlocks, "Hoppers", false, SettingColor.Mode.TEXT);
	private final Kind dispensers = kind(sgBlocks, "Dispensers & Droppers", false, SettingColor.Mode.TEXT);
	private final Kind furnaces = kind(sgBlocks, "Furnaces", false, SettingColor.Mode.SECONDARY);
	private final Kind otherBlocks = kind(sgBlocks, "Other Containers", false, SettingColor.Mode.TEXT);

	private final SettingGroup sgEntities = settings.group("Entities");
	private final Kind chestMinecarts = kind(sgEntities, "Chest Minecarts", true, SettingColor.Mode.YELLOW);
	private final Kind hopperMinecarts = kind(sgEntities, "Hopper Minecarts", false, SettingColor.Mode.TEXT);
	private final Kind furnaceMinecarts = kind(sgEntities, "Furnace Minecarts", false, SettingColor.Mode.SECONDARY);
	private final Kind chestBoats = kind(sgEntities, "Chest Boats", true, SettingColor.Mode.YELLOW);
	private final Kind chestAnimals = kind(sgEntities, "Chest Animals", false, SettingColor.Mode.GREEN);

	private final SettingGroup sgRender = settings.group("Render");
	private final RenderSettings look = RenderSettings.builder(sgRender).build();

	/** Every storage block per chunk, whether or not its kind is shown: toggling a kind only re-meshes. */
	private final ChunkCache<List<Found>> found = ChunkCache.of(this, this::scan)
		.range(range::get)
		.boxes(this::boxes, look)
		.neighbours()
		.build();

	private record Kind(BoolSetting enabled, ColorSetting color) {
	}

	/** {@code tint} is a shulker box's dye, or 0. */
	private record Found(AABB box, Kind kind, int tint) {
	}

	/**
	 * 2: how it's drawn (lids, the mode, merging and the box and outline styles) moved into one Render group. 3: the
	 * render mode is everyone's (the Render window), boxes are plain (no lids, no merging), and the box and outline
	 * options one look.
	 */
	@Override
	public int settingsVersion() {
		return 3;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			for (String s : new String[]{"Lids", "Mode", "Merge", "Shape", "Fill Opacity", "Line Width", "Through Walls"}) saved.move("General", s, "Render", s);
			saved.renameGroup("Outline", "Render");
		}
		if (fromVersion == 2) {
			for (String s : new String[]{"Lids", "Mode", "Merge"}) saved.remove("Render", s);
			RenderSettings.migrate(saved, "Render", null);
		}
	}

	public Storage() {
		super(Tags.RENDER, "Storage", "Highlights chests, shulker boxes and other storage through walls.");
		// A kind turned on or off, or a colour: the boxes are made again from what was found.
		settings.onAnyChanged(s -> found.remeshAll());
	}

	/** A chest, drawn as containers are (see {@link #onPreview}). */
	@Override
	public Widget preview() {
		return Preview.block(Blocks.CHEST);
	}

	/** A preview's container (a block, or a chest minecart, boat or pack animal), as it would be in the world. */
	@Subscribe(inGame = true)
	private void onPreview(PreviewEvent e) {
		Kind k;
		int color;
		if (e.block() != null && e.block().getBlock() instanceof EntityBlock block) {
			BlockEntity be = block.newBlockEntity(BlockPos.ZERO, e.block());
			k = be == null ? null : kindOf(be);
			if (k == null) return;
			color = colorOf(k, tintOf(be));
		} else if (e.entity() != null) {
			k = kindOf(e.entity());
			if (k == null) return;
			color = k.color.argb();
		} else return;
		if (k.enabled.get()) e.render(look, color);
	}

	private Kind kind(SettingGroup group, String name, boolean on, SettingColor.Mode color) {
		BoolSetting enabled = group.bool(name).defaultValue(on).build();
		ColorSetting c = group.color(name + " Color").defaultValue(SettingColor.role(color)).visible(enabled::get).build();
		return new Kind(enabled, c);
	}

	@Override
	public String hudInfo() {
		int[] count = {0};
		found.forEach(list -> {
			for (Found f : list) if (f.kind.enabled.get()) count[0]++;
		});
		return count[0] == 0 ? null : String.valueOf(count[0]);
	}

	/**
	 * Calls {@code out} with the box and colour of every storage block and entity being shown (for Tracers). Nothing
	 * while this module is off.
	 */
	public void forEachShown(float tickDelta, BiConsumer<AABB, Integer> out) {
		if (!isEnabled() || !inGame()) return;
		found.forEach(list -> {
			for (Found f : list) if (f.kind.enabled.get()) out.accept(f.box, color(f));
		});
		for (Entity e : mc.level.entitiesForRendering()) {
			Kind k = kindOf(e);
			if (k != null && k.enabled.get()) out.accept(Entities.lerpedBox(e, tickDelta), k.color.argb());
		}
	}

	/** The containers kept for every chunk, and the storage entities around. */
	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		e.chunks(found);
		for (Entity entity : mc.level.entitiesForRendering()) {
			Kind k = kindOf(entity);
			if (k != null && k.enabled.get()) e.entity(entity, look, k.color.argb());
		}
	}

	// ---- per chunk --------------------------------------------------------------------------------------------------

	private List<Found> scan(LevelChunk chunk) {
		List<Found> list = null;
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			Kind k = kindOf(be);
			if (k == null) continue;
			AABB box = boxOf(be);
			if (box == null) continue;
			if (list == null) list = new ArrayList<>();
			list.add(new Found(box, k, tintOf(be)));
		}
		return list;
	}

	/** A chunk's containers of the kinds shown, as boxes in their colours. */
	private void boxes(List<Found> list, ChunkCache.BoxSink out) {
		for (Found f : list) if (f.kind.enabled.get()) out.box(f.box, color(f));
	}

	private int colorOf(Kind k, int tint) {
		return k == shulkers && !dyedShulkers.get() || tint == 0 ? k.color.argb() : tint;
	}

	private int color(Found f) {
		return colorOf(f.kind, f.tint);
	}

	private Kind kindOf(BlockEntity be) {
		if (be instanceof TrappedChestBlockEntity) return trappedChests;
		if (be instanceof ChestBlockEntity) return chests;
		if (be instanceof EnderChestBlockEntity) return enderChests;
		if (be instanceof BarrelBlockEntity) return barrels;
		if (be instanceof ShulkerBoxBlockEntity) return shulkers;
		if (be instanceof HopperBlockEntity) return hoppers;
		if (be instanceof DispenserBlockEntity) return dispensers;
		if (be instanceof AbstractFurnaceBlockEntity) return furnaces;
		if (be instanceof CrafterBlockEntity || be instanceof BrewingStandBlockEntity) return otherBlocks;
		return null;
	}

	private Kind kindOf(Entity e) {
		if (e instanceof MinecartChest) return chestMinecarts;
		if (e instanceof MinecartHopper) return hopperMinecarts;
		if (e instanceof MinecartFurnace) return furnaceMinecarts;
		if (e instanceof ChestBoat || e instanceof ChestRaft) return chestBoats;
		if (e instanceof AbstractChestedHorse horse && horse.hasChest()) return chestAnimals;
		return null;
	}

	/** A shulker box's dye colour at the kind's alpha, or 0 for an undyed box or anything else. */
	private int tintOf(BlockEntity be) {
		if (!(be instanceof ShulkerBoxBlockEntity box)) return 0;
		DyeColor dye = box.getColor();
		return dye == null ? 0 : ColorUtil.withAlpha(dye.getTextureDiffuseColor(), 255);
	}

	/** The block's outline as one box (both halves of a double chest), or null for the half that isn't drawn. */
	private AABB boxOf(BlockEntity be) {
		BlockPos pos = be.getBlockPos();
		BlockState state = be.getBlockState();
		AABB box = shapeBox(state, pos);
		if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			// Draw a double chest once, from its left half.
			if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) return null;
			BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
			box = box.minmax(shapeBox(mc.level.getBlockState(other), other));
		}
		return box;
	}

	private AABB shapeBox(BlockState state, BlockPos pos) {
		// A shulker box's shape grows while it's open: its box is the closed cube.
		if (state.getBlock() instanceof ShulkerBoxBlock) return new AABB(pos);
		VoxelShape shape = state.getShape(mc.level, pos);
		return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
	}
}
