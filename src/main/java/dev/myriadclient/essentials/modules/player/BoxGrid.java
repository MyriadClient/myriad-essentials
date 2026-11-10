package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.ItemInfo;
import dev.myriadclient.essentials.modules.render.Tooltips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Inventory Tweaks' Box Grid: with a chest open, what every shulker box in it holds, each box a full panel of its 27
 * slots in the theme's window look (hover for an item's tooltip), laid out like a page in rows of columns beside the
 * window, as many whole rows as the screen holds, centred on it; each row filled nearest the window first. Only whole rows show: when there are more than fit,
 * the wheel scrolls the page a row at a time, rows sliding in and out at the edges, a scrollbar at the screen's edge
 * saying where you are.
 * <p>
 * A click on a panel puts that box down with a tab on core's swap bar, as Mod + right-click on it in the chest does, and
 * its panel goes, the box being a click away on the bar. In a box from the bar (or your inventory), the grid stays,
 * showing the chest as last seen: a click there opens the chest again for it.
 */
final class BoxGrid {
	private static final Minecraft mc = Minecraft.getInstance();

	/** Slot sizes to use, largest first: a real container's, smaller only where not one column fits beside the window. */
	private static final int[] CELLS = {18, 16, 14};
	private static final int PAD = 4, GAP = 3, MARGIN = 4, COLS = 9, ROWS = 3, HEADER = 13;
	/** How long no screen may show before the grid starts over (switching on the swap bar closes one before the next opens). */
	private static final int GONE_TICKS = 20;
	/** How fast the page eases to the row scrolled to, and panels fade in and out at its edges (rates, per second). */
	private static final float EASE = 22, FADE = 16;
	/** The scrollbar's width, at the screen's right edge. */
	private static final int BAR_WIDTH = 3;
	/** How long the chest may take to open again for a box clicked from elsewhere. */
	private static final int OPEN_TICKS = 40;

	/** A box's place on the page: the chest slot it's in, what it holds, and where it goes (page coordinates). */
	private record Panel(ItemStack box, List<ItemStack> items, int slot, float x, float y, float w, float h, int cell) {
		/** The box's slot index under the mouse, or -1. */
		int slotAt(double mx, double my, float scroll) {
			int col = (int) Math.floor((mx - x - PAD) / cell), row = (int) Math.floor((my - (y - scroll) - HEADER) / cell);
			if (col < 0 || col >= COLS || row < 0 || row >= ROWS || mx < x + PAD) return -1;
			return row * COLS + col;
		}
	}

	private final InventoryTweaks owner;
	/** The chest the grid is showing, the row scrolled to and where the page is drawn now (easing to that row). */
	private @Nullable BlockPos chestAt;
	private int row;
	private float scroll;
	private long lastFrame;
	/** Last frame's page: its panels, the distance from row to row, the last row it scrolls to, and the window it's around. */
	private List<Panel> panels = List.of();
	private float step, viewTop, viewBottom;
	private int lastRow, rows;
	private int[] window = new int[4];
	/** Each panel's opacity (by chest slot), fading to whether it's wholly in view at the row scrolled to. */
	private final Map<Integer, Float> shown = new HashMap<>();
	/**
	 * The chest's boxes by slot as last seen with it open, shown while you're in a box from the swap bar or your inventory
	 * too (one gone from its slot, put down or taken, left out); and boxes clicked, going down.
	 */
	private final TreeMap<Integer, ItemStack> seen = new TreeMap<>();
	private final Set<Integer> going = new HashSet<>();
	/** Ticks with no screen up. */
	private int closedFor;

	BoxGrid(InventoryTweaks owner) {
		this.owner = owner;
	}

	/** The open chest, if the grid shows for it: plain storage opened from a block (a box goes back to it). */
	private static Containers.@Nullable View chest(AbstractContainerMenu menu) {
		if (!InventoryTweaks.storage(menu)) return null;
		return Myriad.containers().current().filter(v -> v.pos() != null && v.handler() == menu).orElse(null);
	}

	/**
	 * Whether {@code menu}'s screen shows the grid live (the chest itself), or as last seen (a box from the swap bar, your
	 * inventory), or not at all (null): a chest opened is the one shown from then on, till every screen closes.
	 */
	private @Nullable Boolean live(AbstractContainerMenu menu) {
		Containers.View view = chest(menu);
		if (view != null && (chestAt == null || view.pos().equals(chestAt) || !(menu instanceof ShulkerBoxMenu))) {
			if (!view.pos().equals(chestAt)) forget();
			chestAt = view.pos();
			return true;
		}
		return chestAt != null && !seen.isEmpty() ? false : null;
	}

	/** A new chest, or every screen closed: the grid starts over. */
	private void forget() {
		chestAt = null;
		row = 0;
		scroll = 0;
		seen.clear();
		going.clear();
		shown.clear();
	}

	/** Every screen closed: you're done with the chest (its boxes go back). */
	void tick() {
		closedFor = mc.gui.screen() == null ? closedFor + 1 : 0;
		if (closedFor >= GONE_TICKS && chestAt != null) forget();
	}

	// ---- the page ---------------------------------------------------------------------------------------------------

	/**
	 * Lays out the chest's boxes as a page in columns beside the window ({@code left, top, width, height}): as many whole
	 * rows as the screen's height holds, centred on it, then on down past it (a scroll away); each row filled nearest the
	 * window first (right before left, outwards). Live (the chest open), what's seen is the chest's boxes now.
	 */
	private List<Panel> layout(AbstractContainerMenu menu, boolean live, int left, int top, int width, int height) {
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		// Opened again (from the swap bar), its contents may not have arrived yet: what was seen shows till they have.
		Containers.View view = live ? chest(menu) : null;
		if (view != null && view.isLoaded()) {
			seen.clear();
			for (Slot s : menu.slots) if (s.container != mc.player.getInventory() && ItemInfo.isShulkerBox(s.getItem())) seen.put(s.index, s.getItem().copy());
		}
		List<Panel> out = new ArrayList<>();
		rows = 0;
		lastRow = 0;
		if (seen.isEmpty()) return out;
		int cell = CELLS[CELLS.length - 1];
		for (int c : CELLS) {
			if (left - GAP - (COLS * c + 2 * PAD) >= MARGIN || left + width + GAP + COLS * c + 2 * PAD <= sw - MARGIN - BAR_WIDTH) {
				cell = c;
				break;
			}
		}
		float pw = COLS * cell + 2 * PAD, ph = HEADER + ROWS * cell + PAD;
		step = ph + GAP;
		// As many whole rows as the screen's height holds, centred on it: they're what shows, the rest a scroll away.
		int fit = Math.max(1, (int) Math.floor((sh - 2 * MARGIN + GAP) / step));
		viewTop = (sh - (fit * step - GAP)) / 2;
		viewBottom = viewTop + fit * step - GAP;
		List<Float> sides = new ArrayList<>();
		for (int i = 0; ; i++) {
			float right = left + width + GAP + i * (pw + GAP), leftX = left - GAP - pw - i * (pw + GAP);
			boolean r = right + pw <= sw - MARGIN - BAR_WIDTH, l = leftX >= MARGIN;
			if (!r && !l) break;
			if (r) sides.add(right);
			if (l) sides.add(leftX);
		}
		if (sides.isEmpty()) return out;
		float first = viewTop;
		List<Integer> slots = new ArrayList<>(seen.keySet());
		int next = 0;
		for (int r = 0; next < slots.size(); r++) {
			float y = first + r * step;
			for (float x : sides) {
				if (next >= slots.size()) break;
				int slot = slots.get(next++);
				ItemStack box = seen.get(slot);
				out.add(new Panel(box, Tooltips.contents(box), slot, x, y, pw, ph, cell));
			}
			rows = r + 1;
		}
		// The last row scrolled to: the one that brings the page's last row into view.
		lastRow = Math.max(0, rows - fit);
		return out;
	}

	/** The top of where panels show: the rows in view. */
	private float shownTop(Panel p) {
		return viewTop;
	}

	private float shownBottom() {
		return viewBottom;
	}

	/** Whether all of {@code p} is in view with the page scrolled {@code by}. */
	private boolean whole(Panel p, float by) {
		float y = p.y() - by;
		return y >= shownTop(p) - 0.5f && y + p.h() <= shownBottom() + 0.5f;
	}

	// ---- drawing ----------------------------------------------------------------------------------------------------

	void draw(InventoryTweaks.ScreenAccess screen, GuiGraphicsExtractor ctx, int mx, int my) {
		AbstractContainerMenu menu = screen.essentials$menu();
		Boolean live = live(menu);
		if (live == null) {
			panels = List.of();
			return;
		}
		window = screen.essentials$bounds();
		panels = layout(menu, live, window[0], window[1], window[2], window[3]);
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 1 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		// The row scrolled to stays as it was where there are fewer rows for a moment (a chest opened again, still arriving).
		float goal = Math.max(0, Math.min(row, lastRow)) * step, ease = 1 - (float) Math.exp(-EASE * dt), fade = 1 - (float) Math.exp(-FADE * dt);
		scroll = Math.abs(goal - scroll) < 0.5f ? goal : scroll + (goal - scroll) * ease;
		// Wholly in view at the row scrolled to, or not: each fades that way (opened, the grid shows at once).
		boolean opening = shown.isEmpty();
		for (Panel p : panels) {
			float want = whole(p, goal) ? 1 : 0, was = shown.getOrDefault(p.slot(), opening ? want : 0);
			float to = Math.abs(want - was) < 0.02f ? want : was + (want - was) * fade;
			shown.put(p.slot(), to);
		}
		if (panels.isEmpty()) return;
		ItemStack[] hovered = {null};
		Myriad.ui().draw(ctx, c -> {
			for (Panel p : panels) {
				float alpha = shown.get(p.slot()) * (going.contains(p.slot()) ? 0.45f : 1);
				if (alpha <= 0.01f) continue;
				c.push();
				if (alpha < 1) c.alpha(alpha);
				drawPanel(c, p, onPanel(p, mx, my) ? mx : Integer.MIN_VALUE, my, hovered);
				c.pop();
			}
			drawScrollbar(c);
		});
		if (hovered[0] != null && menu.getCarried().isEmpty()) ctx.setTooltipForNextFrame(mc.font, hovered[0], mx, my);
	}

	/** Whether the mouse is on a panel in view (on the part of it that shows, while the page moves). */
	private boolean onPanel(Panel p, double mx, double my) {
		if (shown.getOrDefault(p.slot(), 0f) < 0.5f) return false;
		float y = p.y() - scroll;
		return mx >= p.x() && mx < p.x() + p.w() && my >= Math.max(y, shownTop(p)) && my < Math.min(y + p.h(), shownBottom());
	}

	/**
	 * A panel in the theme's window look, cut off where it moves out of where it shows (only while the page moves): its
	 * frame clipped there, its items only in slots wholly in view (items aren't clipped).
	 */
	private void drawPanel(Canvas c, Panel p, int mx, int my, ItemStack[] hovered) {
		ThemeSettings theme = Myriad.ui().theme();
		float x = p.x(), y = p.y() - scroll, w = p.w(), h = p.h(), top = shownTop(p), bottom = shownBottom();
		float r = Math.min(theme.rounding.get(), 6);
		c.push();
		c.clip(x - 2, top, w + 4, bottom - top);
		c.backdrop(x, y, w, h, r, 1);
		c.roundRect(x, y, w, h, r, ColorUtil.withAlpha(theme.windowBackground.argb(), Math.max(200, ColorUtil.alpha(theme.windowBackground.argb()))));
		c.gradientOutline(x, y, w, h, r, Math.max(1, theme.borderSize.get()), theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
		boolean empty = p.items().stream().allMatch(ItemStack::isEmpty);
		String name = p.box().getHoverName().getString() + (empty ? " (empty)" : "");
		float size = c.defaultFontSize() * 0.85f;
		c.text(FontFamily.SANS_BOLD, size, c.ellipsize(FontFamily.SANS_BOLD, size, name, w - 2 * PAD), x + PAD,
			y + (HEADER - c.textHeight(FontFamily.SANS_BOLD, size)) / 2 + 1, theme.text.argb());
		int under = mx == Integer.MIN_VALUE ? -1 : p.slotAt(mx, my, scroll);
		int cell = ColorUtil.withAlpha(theme.surface.argb(), 110);
		float gx = x + PAD, gy = y + HEADER, item = Math.min(16, p.cell() - 2);
		for (int i = 0; i < ROWS * COLS; i++) {
			float sx = gx + (i % COLS) * p.cell(), sy = gy + (i / COLS) * p.cell();
			c.roundRect(sx + 0.5f, sy + 0.5f, p.cell() - 1, p.cell() - 1, 2, i == under ? theme.surfaceHover.argb() : cell);
		}
		c.pop();
		for (int i = 0; i < ROWS * COLS; i++) {
			float sx = gx + (i % COLS) * p.cell(), sy = gy + (i / COLS) * p.cell();
			ItemStack s = p.items().get(i);
			if (s.isEmpty() || sy < top || sy + p.cell() > bottom) continue;
			c.item(s, sx + (p.cell() - item) / 2, sy + (p.cell() - item) / 2, item, true);
			if (i == under) hovered[0] = s;
		}
	}

	/** A thin bar at the screen's right edge, its thumb the share of the rows in view, where they are. */
	private void drawScrollbar(Canvas c) {
		if (lastRow <= 0) return;
		ThemeSettings theme = Myriad.ui().theme();
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		float track = viewBottom - viewTop, thumb = Math.max(12, track * (rows - lastRow) / rows), at = viewTop + (track - thumb) * scroll / (lastRow * step);
		c.roundRect(sw - BAR_WIDTH - 1, viewTop, BAR_WIDTH, track, BAR_WIDTH / 2f, ColorUtil.withAlpha(theme.surface.argb(), 120));
		c.roundRect(sw - BAR_WIDTH - 1, at, BAR_WIDTH, thumb, BAR_WIDTH / 2f, theme.accent.argb());
	}

	// ---- input ------------------------------------------------------------------------------------------------------

	/** A click at {@code mx, my}: on a panel, its box goes down with a tab on the swap bar. True if it was on one (it goes no further). */
	boolean press(InventoryTweaks.ScreenAccess screen, double mx, double my) {
		AbstractContainerMenu menu = screen.essentials$menu();
		Boolean live = live(menu);
		if (live == null || chestAt == null) return false;
		for (Panel p : panels) {
			if (!onPanel(p, mx, my)) continue;
			if (!menu.getCarried().isEmpty() || !going.add(p.slot())) return true;
			int slot = p.slot();
			// From a box on the swap bar or your inventory: the chest first, opened again for it.
			CompletableFuture<Containers.View> chest = live ? CompletableFuture.completedFuture(chest(menu))
				: Myriad.containers().open(owner, chestAt, OPEN_TICKS, Placement.Options.forServer());
			chest.thenCompose(v -> owner.putDown(v, slot)).whenComplete((session, t) -> going.remove(slot));
			return true;
		}
		return false;
	}

	/** The wheel at {@code mx, my}: off the window, with more rows than show, it scrolls a row. */
	boolean scrolled(InventoryTweaks.ScreenAccess screen, double mx, double my, double amount) {
		if (panels.isEmpty() || lastRow <= 0 || amount == 0) return false;
		int[] r = screen.essentials$bounds();
		if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return false;
		row = Math.max(0, Math.min(lastRow, Math.min(row, lastRow) + (amount < 0 ? 1 : -1)));
		return true;
	}
}
