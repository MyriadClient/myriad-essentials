package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.BoxStyle;
import dev.myriadclient.api.render.HighlightSettings;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.essentials.modules.world.AirPlace;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingGroup;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Replaces vanilla's thin black outline around the block you're looking at: a shader outline (with glow, a solid or
 * dotted fill and a gradient, like ESP's) or a box, either following the block's real shape. Always just the one block
 * (half a double chest, one end of a bed), outlined on its own even where Blocks or Storage highlight its neighbours.
 * With nothing under the crosshair, it outlines the space Air Place would put a block in (a full block).
 */
public class BlockHighlight extends Module {
	public enum Mode {
		OUTLINE, BOX
	}

	private final SettingGroup sgRender = settings.group("Render");
	private final EnumSetting<Mode> mode = sgRender.oneOf("Mode", Mode.OUTLINE).description("Outline: a shader outline around the block's shape. Box: a box with fill and lines.").build();
	private final ColorSetting color = sgRender.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	private final BoxStyle box = new BoxStyle(sgRender, () -> mode.get() == Mode.BOX, false);
	private final HighlightSettings outline = HighlightSettings.builder(sgRender).visible(() -> mode.get() == Mode.OUTLINE).sharing(box).build();

	/** 2: everything moved into one Render group (the box and outline styles were groups of their own). */
	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			for (String s : new String[]{"Mode", "Color"}) saved.move("General", s, "Render", s);
			saved.renameGroup("Box", "Render");
			saved.renameGroup("Outline", "Render");
		}
	}

	public BlockHighlight() {
		super(Tags.RENDER, "Block Highlight", "Your own look for the block you're looking at, in place of vanilla's outline.");
		// Vanilla's outline only while this is off.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, state) -> !isEnabled());
	}

	/** A block as the highlight looks on it: the box in Box mode, its outline's edges in Outline mode (drawn by a shader in the world). */
	@Override
	public Widget preview() {
		return Preview.block(Blocks.STONE).decorate((shapes, cube, entity) -> {
			int c = color.argb();
			if (mode.get() == Mode.BOX) box.draw(shapes, cube, c, 1);
			else shapes.lineWidth(Math.max(1, outline.outlineWidth.getFloat())).boxLines(cube, c, false);
		});
	}

	/** The block under the crosshair, or where Air Place would place with nothing under it, or null. */
	private BlockPos target() {
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return AirPlace.target();
		return mc.level.getBlockState(hit.getBlockPos()).isAir() ? null : hit.getBlockPos();
	}

	/**
	 * The block this highlights with its outline right now, or null: other modules' highlights leave it out (Storage
	 * does), so it shows this look rather than theirs.
	 */
	public static BlockPos highlighted() {
		BlockHighlight m = Modules.active(BlockHighlight.class);
		return m != null && m.mode.get() == Mode.OUTLINE && m.inGame() ? m.target() : null;
	}

	/**
	 * On the layer under other modules' highlights: the block you're looking at, often right in front of you, would
	 * otherwise hide every highlight behind it on screen (all the chests behind a wall you look at).
	 */
	@Subscribe(inGame = true)
	private void onHighlight(HighlightEvent.Shapes e) {
		if (mode.get() != Mode.OUTLINE) return;
		BlockPos pos = target();
		if (pos != null) e.below().block(pos, outline.style(), color.argb());
	}

	@Subscribe(inGame = true)
	private void onRender(Render3DEvent e) {
		if (mode.get() != Mode.BOX) return;
		BlockPos pos = target();
		if (pos == null) return;
		int c = color.argb();
		e.shapes().lineWidth(box.lineWidth.getFloat());
		e.shapes().blockShape(pos, box.fill(c, 1), box.line(c, 1), box.shape.get(), box.throughWalls.get());
	}
}
