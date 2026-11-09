package dev.myriadclient.essentials.modules.render.tab;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.PlayerHeads;
import dev.myriadclient.api.util.TickCached;
import dev.myriadclient.essentials.modules.render.ExtraTab;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Draws the player list on the canvas. Everything is drawn by what it's drawn from, shapes, then faces, then text, so
 * the number of draws doesn't grow with the number of players.
 */
public final class TabRenderer {
	private static final float BAR_W = 1.5f, BAR_STEP = 2;
	private static final int BAR_OFF = 0x40808080;

	private final ExtraTab module;
	private final HeadAtlas heads = new HeadAtlas();
	private final TickCached<TabModel> model = TickCached.of(this::build);
	private final Consumer<Canvas> drawer = this::draw;
	private @Nullable Canvas canvas;
	private int screenW, screenH;
	private @Nullable Scoreboard scoreboard;
	private @Nullable Objective objective;
	private @Nullable Component header, footer;
	private boolean pending;

	public TabRenderer(ExtraTab module) {
		this.module = module;
	}

	/** The list is showing this frame; it's drawn by {@link #drawPending}, once the HUD is. */
	public void show(int screenWidth, Scoreboard scoreboard, @Nullable Objective objective, @Nullable Component header, @Nullable Component footer) {
		screenW = screenWidth;
		this.scoreboard = scoreboard;
		this.objective = objective;
		this.header = header;
		this.footer = footer;
		pending = true;
	}

	public void drawPending(GuiGraphicsExtractor graphics) {
		if (!pending) return;
		pending = false;
		screenH = graphics.guiHeight();
		graphics.nextStratum();
		Myriad.ui().draw(graphics, drawer);
	}

	/** Lets go of the faces' texture. */
	public void close() {
		heads.close();
		model.invalidate();
	}

	private TabModel build() {
		return TabModel.build(module, canvas, heads, screenW, screenH, scoreboard, objective, header, footer);
	}

	private void draw(Canvas c) {
		canvas = c;
		TabModel m = model.get();
		canvas = null;
		c.roundRect(m.panelX, m.panelY, m.panelW, m.panelH, m.radius, m.panelColor);
		if (m.borderFrom != 0) c.gradientOutline(m.panelX, m.panelY, m.panelW, m.panelH, m.radius, 1, m.borderFrom, m.borderTo, m.borderAngle);
		if (m.separator != 0) for (float y : m.separators) c.rect(m.panelX + 4, y, m.panelW - 8, 1, m.separator);
		for (TabModel.Row r : m.rows) {
			if (r.fill != 0) c.roundRect(r.x, r.y, r.w, m.rowHeight, 2, r.fill);
			if (r.bars != 0 || r.pingColor != 0 && r.ping == null) drawBars(c, r, m.rowHeight);
		}
		drawHeads(c, m);
		for (TabModel.Row r : m.rows) {
			drawText(c, m, r.name, r.nameX, r.textY);
			if (r.score != null) drawText(c, m, r.score, r.scoreX, r.textY);
			if (r.ping != null) c.text(m.font, m.fontSize, r.ping, r.pingX, r.textY, r.pingColor);
		}
		for (TabModel.Line l : m.lines) drawText(c, m, l.text, l.x, l.y);
	}

	private static void drawBars(Canvas c, TabModel.Row r, float rowH) {
		for (int i = 0; i < 5; i++) {
			float h = (i + 1) / 5f * (rowH - 3);
			c.rect(r.barsX + i * BAR_STEP, r.y + rowH - 1.5f - h, BAR_W, h, i < r.bars ? r.pingColor : BAR_OFF);
		}
	}

	private void drawHeads(Canvas c, TabModel m) {
		GpuTextureView atlas = heads.view();
		if (atlas != null) {
			for (TabModel.Row r : m.rows) {
				if (r.cell == null) continue;
				HeadAtlas.Cell cell = r.cell;
				float top = r.flip ? cell.v1 : cell.v0, bottom = r.flip ? cell.v0 : cell.v1;
				c.texture(atlas, r.headX, r.y + (m.rowHeight - 8) / 2, 8, 8, cell.u0, top, cell.u1, bottom, r.tint);
			}
		}
		for (TabModel.Row r : m.unbaked) PlayerHeads.draw(c, r.skin, r.headX, r.y + (m.rowHeight - 8) / 2, 8, r.tint);
	}

	private static void drawText(Canvas c, TabModel m, TabModel.Text text, float x, float y) {
		for (int i = 0; i < text.parts.length; i++) c.text(m.font, m.fontSize, text.parts[i], x + text.offsets[i], y, text.colors[i]);
	}
}
