package dev.myriadclient.essentials.modules.render.tab;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Every listed player's face (with its hat, if they show it) copied into one texture, so a list of hundreds of skins
 * is one draw and not one each. Downloaded skins keep their pixels in memory, which is what's copied, once a skin;
 * a skin that doesn't (Minecraft's built-in ones) has no cell and is drawn from its own texture.
 */
final class HeadAtlas {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("myriad-essentials", "tab_heads");
	private static final int SIZE = 1024;
	/** A skin pixel takes this many atlas pixels, since the canvas samples smoothly and the faces should stay square. */
	private static final int SCALE = 3;
	private static final int CELL = 8 * SCALE;
	/** A cell and the empty line around it, so one face doesn't bleed into the next. */
	private static final int PITCH = CELL + 2;
	private static final int PER_LINE = SIZE / PITCH;
	private static final int CAPACITY = PER_LINE * PER_LINE;
	private static final int SKIN_SIZE = 64;

	static final class Cell {
		private DynamicTexture source;
		private final int x, y;
		final float u0, v0, u1, v1;

		private Cell(DynamicTexture source, int x, int y) {
			this.source = source;
			this.x = x;
			this.y = y;
			float inset = 0.5f / SIZE;
			u0 = x / (float) SIZE + inset;
			v0 = y / (float) SIZE + inset;
			u1 = (x + CELL) / (float) SIZE - inset;
			v1 = (y + CELL) / (float) SIZE - inset;
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final Map<Identifier, Cell> faces = new HashMap<>();
	private final Map<Identifier, Cell> withHats = new HashMap<>();
	private @Nullable DynamicTexture atlas;
	private @Nullable Object connection;
	private int used;
	private boolean dirty;

	/** The cell for this skin, baked if it's new; null if its pixels can't be read. */
	@Nullable Cell cell(Identifier skin, boolean hat) {
		if (connection != mc.getConnection()) reset();
		AbstractTexture texture = mc.getTextureManager().getTexture(skin);
		if (!(texture instanceof DynamicTexture source) || source.getPixels() == null) return null;
		NativeImage pixels = source.getPixels();
		if (pixels.getWidth() != SKIN_SIZE || pixels.getHeight() != SKIN_SIZE) return null;
		Map<Identifier, Cell> cells = hat ? withHats : faces;
		Cell cell = cells.get(skin);
		if (cell == null) {
			if (used == CAPACITY) reset();
			if (atlas == null) create();
			cell = new Cell(source, used % PER_LINE * PITCH + 1, used / PER_LINE * PITCH + 1);
			used++;
			cells.put(skin, cell);
			bake(cell, pixels, hat);
		} else if (cell.source != source) {
			cell.source = source;
			bake(cell, pixels, hat);
		}
		return cell;
	}

	/** Sends what was baked since last time to the graphics card, all at once. */
	void upload() {
		if (!dirty || atlas == null) return;
		atlas.upload();
		dirty = false;
	}

	@Nullable GpuTextureView view() {
		return atlas == null ? null : atlas.getTextureView();
	}

	void close() {
		reset();
		if (atlas != null) mc.getTextureManager().release(ID);
		atlas = null;
	}

	private void create() {
		NativeImage image = new NativeImage(SIZE, SIZE, true);
		atlas = new DynamicTexture(ID::toString, image);
		mc.getTextureManager().register(ID, atlas);
	}

	private void reset() {
		faces.clear();
		withHats.clear();
		used = 0;
		connection = mc.getConnection();
	}

	private void bake(Cell cell, NativeImage skin, boolean hat) {
		NativeImage image = atlas.getPixels();
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				int color = skin.getPixel(8 + x, 8 + y);
				if (hat) color = ARGB.alphaBlend(color, skin.getPixel(40 + x, 8 + y));
				image.fillRect(cell.x + x * SCALE, cell.y + y * SCALE, SCALE, SCALE, color);
			}
		}
		dirty = true;
	}
}
