package dev.myriadclient.essentials.modules.combat;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Format;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Auto Disconnect's report, the body of the disconnect screen when it made you leave (DisconnectedScreenMixin), drawn
 * as Myriad draws its own panels: a header (what happened, and when, counting up), the setting that tripped as a note,
 * then the screen as you left in a card (a click shows it bigger) beside what was going on, under headings: you, what
 * was around you, the connection. What made you leave is in red. With Themed Screens off, plainer: Minecraft's colours,
 * square corners.
 */
public final class DisconnectReport extends AbstractWidget {
	/** Between the picture and the lines, around the picture inside its card, inside the note. */
	private static final float GAP = 14, CARD_PAD = 3, NOTE_PAD = 5;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final Map<AutoDisconnect.Section, String> SECTIONS = Map.of(AutoDisconnect.Section.YOU, "You", AutoDisconnect.Section.AROUND,
		"Around you", AutoDisconnect.Section.CONNECTION, "Connection");

	/** The look: the theme's, or a plainer one without Themed Screens. */
	private record Style(int text, int dim, int cause, int accent, int surface, int border, int window, float rounding) {
		static Style now() {
			ThemeSettings t = Myriad.ui().theme();
			if (t.themedScreens.get()) {
				return new Style(t.text.argb(), t.textDim.argb(), t.red.argb(), t.accent.argb(), t.surface.argb(), ColorUtil.withAlpha(t.inactiveBorder.argb(), 0xD0),
					t.windowBackground.argb(), Math.min(t.rounding.get(), 6));
			}
			return new Style(0xFFFFFFFF, 0xFFA0A0A0, 0xFFFF5555, 0xFF55FF55, 0x60000000, 0xFF808080, 0xC0101010, 0);
		}
	}

	private final AutoDisconnect module;
	private final AutoDisconnect.Report report;
	private final String clock;
	private final Map<AutoDisconnect.Section, List<AutoDisconnect.Row>> sections = new EnumMap<>(AutoDisconnect.Section.class);
	/** Sizes from the theme's font size: a line of text, the title's size, the header, the note, a heading, a row, the chart. */
	private final float size, line, titleSize, header, note, heading, row, chart;
	/** The picture inside its card; 0 wide for none. */
	private final float pictureWidth, pictureHeight;

	private DisconnectReport(AutoDisconnect module, AutoDisconnect.Report report, int width, int maxHeight, boolean picture) {
		super(0, 0, width, 0, Component.literal("Auto Disconnect report"));
		this.module = module;
		this.report = report;
		clock = LocalDateTime.ofInstant(Instant.ofEpochMilli(report.at()), ZoneId.systemDefault()).format(CLOCK);
		for (AutoDisconnect.Row r : report.rows()) sections.computeIfAbsent(r.section(), s -> new ArrayList<>()).add(r);

		size = Myriad.ui().theme().fontSize.get().floatValue();
		line = size * 1.4f;
		titleSize = size * 1.4f;
		header = titleSize * 1.4f + 2 + line;
		note = line + NOTE_PAD * 2;
		heading = size * 0.85f * 1.4f + 10;
		row = Math.round(size * 1.85f);
		chart = 18;
		float details = 0;
		for (List<AutoDisconnect.Row> rows : sections.values()) details += heading + rows.size() * row;
		if (report.health().length > 1) details += chart;

		var window = Minecraft.getInstance().getWindow();
		float aspect = window.getHeight() > 0 ? (float) window.getWidth() / window.getHeight() : 16 / 9f;
		float bodyRoom = maxHeight - header - note - 22;
		float pw = 0, ph = 0;
		if (picture) {
			pw = width * 0.5f - CARD_PAD * 2;
			ph = pw / aspect;
			if (ph > bodyRoom - CARD_PAD * 2) {
				ph = Math.max(40, bodyRoom - CARD_PAD * 2);
				pw = ph * aspect;
			}
		}
		pictureWidth = pw;
		pictureHeight = ph;
		height = Math.round(header + 8 + note + 14 + Math.max(details, picture ? ph + CARD_PAD * 2 : 0));
	}

	/** The report {@code width} wide and no taller than {@code maxHeight}, with the picture beside it if one was taken. */
	public static DisconnectReport of(AutoDisconnect module, AutoDisconnect.Report report, int width, int maxHeight, boolean picture) {
		return new DisconnectReport(module, report, width, maxHeight, picture);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Myriad.ui().draw(g, c -> paint(c, mouseX, mouseY));
	}

	private void paint(Canvas c, float mx, float my) {
		Style s = Style.now();
		float x = getX(), y = getY(), w = width;

		// What happened and when: a badge, the title over the reason, the time over how long ago.
		float badge = header * 0.42f, bx = x + badge, by = y + header / 2;
		c.circle(bx, by, badge, ColorUtil.withAlpha(s.cause, 0x30));
		float iconSize = size * 1.3f;
		c.text(FontFamily.MONO, iconSize, Icons.POWER, bx - c.textWidth(FontFamily.MONO, iconSize, Icons.POWER) / 2, by - c.textHeight(FontFamily.MONO, iconSize) / 2, s.cause);
		float tx = x + badge * 2 + 10, ty = y + (header - (c.textHeight(FontFamily.SANS_BOLD, titleSize) + 2 + c.textHeight())) / 2;
		c.text(FontFamily.SANS_BOLD, titleSize, "Auto Disconnect", tx, ty, s.text);
		String ago = Format.duration(System.currentTimeMillis() - report.at()) + " ago";
		float right = x + w, clockSize = size * 1.15f;
		float side = Math.max(c.textWidth(FontFamily.SANS_BOLD, clockSize, clock), c.textWidth(ago)) + 12;
		c.text(c.ellipsize(FontFamily.SANS, size, report.reason(), right - side - tx), tx, ty + c.textHeight(FontFamily.SANS_BOLD, titleSize) + 2, s.cause);
		c.text(FontFamily.SANS_BOLD, clockSize, clock, right - c.textWidth(FontFamily.SANS_BOLD, clockSize, clock), ty, s.text);
		c.text(ago, right - c.textWidth(ago), ty + c.textHeight(FontFamily.SANS_BOLD, titleSize) + 2, s.dim);

		// The setting that tripped, as a note.
		float ny = y + header + 8;
		c.roundRect(x, ny, w, note, Math.min(4, s.rounding), ColorUtil.withAlpha(s.cause, 26));
		c.roundRect(x, ny + 2, 2, note - 4, 1, ColorUtil.withAlpha(s.cause, 200));
		c.text(FontFamily.MONO, size, Icons.WARNING, x + 7, ny + (note - c.textHeight(FontFamily.MONO, size)) / 2, s.cause);
		c.text(report.trigger(), x + 7 + c.textWidth(FontFamily.MONO, size, Icons.WARNING) + 6, ny + (note - c.textHeight()) / 2, s.text);

		float top = ny + note + 14, dx = x;
		if (pictureWidth > 0) {
			picture(c, s, x, top, mx, my);
			dx = x + pictureWidth + CARD_PAD * 2 + GAP;
		}
		details(c, s, dx, top, x + w - dx);
	}

	/** The screen as you left, in a card; lit and captioned under the mouse, for a click to see it bigger. */
	private void picture(Canvas c, Style s, float x, float y, float mx, float my) {
		float cw = pictureWidth + CARD_PAD * 2, ch = pictureHeight + CARD_PAD * 2;
		boolean shown = module.shotWidth() > 0, hover = shown && overPicture(mx, my);
		c.roundRect(x, y, cw, ch, s.rounding, s.surface);
		if (shown) {
			c.texture(AutoDisconnect.SCREENSHOT, x + CARD_PAD, y + CARD_PAD, pictureWidth, pictureHeight, 0, 0, 1, 1, -1);
		} else {
			String wait = "Taking the picture...";
			c.text(wait, x + (cw - c.textWidth(wait)) / 2, y + (ch - c.textHeight()) / 2, s.dim);
		}
		c.outline(x, y, cw, ch, s.rounding, 1, hover ? s.accent : s.border);
		if (hover) {
			String caption = "Click to see it bigger";
			float pw = c.textWidth(caption) + 14, ph = line + 4, px = x + (cw - pw) / 2, py = y + ch - ph - 7;
			c.roundRect(px, py, pw, ph, ph / 2, ColorUtil.withAlpha(s.window, 0xE8));
			c.text(caption, px + 7, py + (ph - c.textHeight()) / 2, s.text);
		}
	}

	/** What was going on, under headings: a label and its value a line each, what made you leave in red. */
	private void details(Canvas c, Style s, float x, float y, float w) {
		float labelWidth = 0;
		for (AutoDisconnect.Row r : report.rows()) labelWidth = Math.max(labelWidth, c.textWidth(r.label()));
		labelWidth += 12;
		float vx = x + labelWidth, vw = w - labelWidth, cy = y;
		boolean first = true;
		for (var e : sections.entrySet()) {
			cy += first ? 0 : 6;
			first = false;
			heading(c, s, SECTIONS.get(e.getKey()), x, cy, w);
			cy += heading - 6;
			for (AutoDisconnect.Row r : e.getValue()) {
				float text = cy + (row - c.textHeight()) / 2;
				c.text(r.label(), x, text, s.dim);
				c.text(c.ellipsize(c.defaultFont(), size, r.value(), vw), vx, text, r.cause() ? s.cause : s.text);
				cy += row;
				if (r.label().equals("Health") && report.health().length > 1) {
					float hs = size * 0.85f;
					String span = "last " + Math.round(report.health().length / 20f) + " s";
					c.text(FontFamily.SANS, hs, span, x, cy + (chart - 4 - c.textHeight(FontFamily.SANS, hs)) / 2, ColorUtil.withAlpha(s.dim, 0xB0));
					chart(c, s, vx, cy, vw, chart - 4);
					cy += chart;
				}
			}
		}
	}

	/** A heading as Myriad's are: small and bold, a faint line to the right. */
	private void heading(Canvas c, Style s, String title, float x, float y, float w) {
		float hs = size * 0.85f, mid = y + c.textHeight(FontFamily.SANS_BOLD, hs) / 2;
		c.text(FontFamily.SANS_BOLD, hs, title, x, y, s.dim);
		float after = x + c.textWidth(FontFamily.SANS_BOLD, hs, title) + 5;
		if (x + w - after > 4) c.rect(after, mid, x + w - after, 1, ColorUtil.withAlpha(s.dim, 40));
	}

	/** Your health over the ticks before you left, oldest at the left: bars in a rounded strip, red where it was low. */
	private void chart(Canvas c, Style s, float x, float y, float w, float h) {
		float[] health = report.health();
		float max = Math.max(1, report.maxHealth());
		c.roundRect(x, y, w, h, Math.min(3, s.rounding), ColorUtil.withAlpha(s.surface, Math.min(0xFF, ColorUtil.alpha(s.surface) + 0x20)));
		float inner = h - 4, step = Math.max(1, (w - 4) / health.length);
		int bars = (int) ((w - 4) / step);
		for (int i = 0; i < bars; i++) {
			float v = Math.clamp(health[Math.min(health.length - 1, (int) ((long) i * health.length / bars))] / max, 0, 1);
			float bh = v * inner;
			if (bh > 0.5f) c.rect(x + 2 + i * step, y + 2 + inner - bh, Math.max(1, step - (step > 2 ? 0.5f : 0)), bh,
				ColorUtil.withAlpha(v <= 0.3f ? s.cause : s.accent, 0xC0));
		}
	}

	private boolean overPicture(double mx, double my) {
		float top = getY() + header + 8 + note + 14;
		return pictureWidth > 0 && mx >= getX() && my >= top && mx < getX() + pictureWidth + CARD_PAD * 2 && my < top + pictureHeight + CARD_PAD * 2;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || module.shotWidth() == 0 || !overPicture(event.x(), event.y())) return false;
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new PictureScreen(mc.gui.screen(), module.shotWidth(), module.shotHeight()));
		return true;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal("Auto Disconnect: " + report.reason()));
	}

	/** The picture as big as the screen allows, in its card; a click or Escape goes back. */
	private static final class PictureScreen extends Screen {
		private final Screen parent;
		private final int imageWidth, imageHeight;

		PictureScreen(Screen parent, int imageWidth, int imageHeight) {
			super(Component.literal("Your screen as you left"));
			this.parent = parent;
			this.imageWidth = imageWidth;
			this.imageHeight = imageHeight;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			super.extractRenderState(g, mouseX, mouseY, a);
			Myriad.ui().draw(g, c -> {
				Style s = Style.now();
				float room = 20, top = 30, bottom = 26;
				float scale = Math.min((width - 2 * room) / imageWidth, (height - top - bottom) / imageHeight);
				float w = imageWidth * scale, h = imageHeight * scale, x = (width - w) / 2, y = top + (height - top - bottom - h) / 2;
				String title = getTitle().getString(), back = "Click or press Escape to go back";
				c.text(FontFamily.SANS_BOLD, c.defaultFontSize() * 1.15f, title, (width - c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize() * 1.15f, title)) / 2, 10, s.text);
				c.roundRect(x - CARD_PAD, y - CARD_PAD, w + CARD_PAD * 2, h + CARD_PAD * 2, s.rounding, s.surface);
				c.texture(AutoDisconnect.SCREENSHOT, x, y, w, h, 0, 0, 1, 1, -1);
				c.outline(x - CARD_PAD, y - CARD_PAD, w + CARD_PAD * 2, h + CARD_PAD * 2, s.rounding, 1, s.border);
				c.text(back, (width - c.textWidth(back)) / 2, height - bottom / 2 - c.textHeight() / 2, s.dim);
			});
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			onClose();
			return true;
		}

		@Override
		public void onClose() {
			minecraft.gui.setScreen(parent);
		}
	}
}
