package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PreviewEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.setting.ColorSetting;
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
 * Replaces vanilla's thin black outline around the block you're looking at with your own look, in the render mode (the
 * Render window): an outline around the block's shape in Shader mode (with glow, fill and gradient, like ESP's), a box
 * in Box mode. Always just the one block (half a double chest, one end of a bed), outlined on its own even where Blocks
 * or Storage highlight its neighbours. With nothing under the crosshair, it outlines the space Air Place would put a
 * block in (a full block).
 */
public class BlockHighlight extends Module {
	private final SettingGroup sgRender = settings.group("Render");
	private final ColorSetting color = sgRender.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	private final RenderSettings look = RenderSettings.builder(sgRender).throughWalls(false).build();

	/**
	 * 2: everything moved into one Render group (the box and outline styles were groups of their own). 3: the render
	 * mode is everyone's (the Render window), and the box and outline options one look.
	 */
	@Override
	public int settingsVersion() {
		return 3;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			for (String s : new String[]{"Mode", "Color"}) saved.move("General", s, "Render", s);
			saved.renameGroup("Box", "Render");
			saved.renameGroup("Outline", "Render");
		}
		if (fromVersion == 2) {
			saved.remove("Render", "Mode");
			RenderSettings.migrate(saved, "Render", null);
		}
	}

	public BlockHighlight() {
		super(Tags.RENDER, "Block Highlight", "Your own look for the block you're looking at, in place of vanilla's outline.");
		// Vanilla's outline only while this is off.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, state) -> !isEnabled());
	}

	/** A block as the highlight looks on it (see {@link #onPreview}). */
	@Override
	public Widget preview() {
		return Preview.block(Blocks.STONE);
	}

	/** A preview's block as if you looked at it, unless another module's look is on it already (Storage on a chest). */
	@Subscribe(inGame = true, priority = Priority.LOW)
	private void onPreview(PreviewEvent e) {
		if (e.block() != null && !e.highlighted()) e.render(look, color.argb());
	}

	/** The block under the crosshair, or where Air Place would place with nothing under it, or null. */
	private BlockPos target() {
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return AirPlace.target();
		return mc.level.getBlockState(hit.getBlockPos()).isAir() ? null : hit.getBlockPos();
	}

	/**
	 * Under other modules' shapes: the block you're looking at, often right in front of you, would otherwise hide every
	 * highlight behind it on screen (all the chests behind a wall you look at).
	 */
	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		BlockPos pos = target();
		if (pos != null) e.below().block(pos, look, color.argb());
	}
}
