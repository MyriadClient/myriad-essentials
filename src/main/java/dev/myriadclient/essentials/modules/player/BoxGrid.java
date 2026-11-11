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
import dev.myriadclient.essentials.util.Menus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Inventory Tweaks' Box Grid: with a chest open, what every shulker box in it holds, each box a full panel of its 27
 * slots in the theme's window look (hover for an item's tooltip), laid out like a page in rows of columns beside the
 * window, as many whole rows as the screen holds (six at most), centred on it beside the window; each row filled nearest the window first. Only whole rows show: when there are more than fit,
 * the wheel scrolls the page a row at a time, rows sliding in and out at the edges, a scrollbar at the screen's edge
 * saying where you are.
 * <p>
 * With the chest open, a click on a box's panel puts that box down and gives it a tab on the swap bar, as Mod +
 * right-click does (another addon may handle the panels instead: {@link InventoryTweaks#BOX_GRID}). In a box from the
 * swap bar (or your inventory), the grid stays, showing the chest as last seen: a click there opens the chest again.
 */
final class BoxGrid {
	private static final Minecraft mc = Minecraft.getInstance();

	/** Slot sizes to use, largest first: a real container's, smaller only where not one column fits beside the window. */
	private static final int[] CELLS = {18, 16, 14};
	private static final int PAD = 4, GAP = 3, MARGIN = 4, COLS = 9, ROWS = 3, HEADER = 13;
	/** The most rows of panels that show at once; more are a scroll away. */
	private static final int MAX_ROWS = 6;
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

	/** Who handles the chest's panels, if someone does (InventoryTweaks.BOX_GRID, asked as the chest's grid starts). */
	private InventoryTweaks.@Nullable GridHandler handler;

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
			if (!view.pos().equals(chestAt)) {
				forget();
				handler = InventoryTweaks.BOX_GRID.call(owner, new InventoryTweaks.GridChest(view.pos()), c -> null);
			}
			chestAt = view.pos();
			return true;
		}
		return chestAt != null && !seen.isEmpty() ? false : null;
	}

	/** A new chest, or every screen closed: the grid starts over. */
	private void forget() {
		if (handler != null) {
			if (handler.busy()) return;
			handler.done();
			handler = null;
		}
		chestAt = null;
		row = 0;
		scroll = 0;
		seen.clear();
		going.clear();
		shown.clear();
	}

	/** Inventory Tweaks turned off: the grid lets go of the chest (its boxes go back). */
	void off() {
		if (handler != null) handler.done();
		handler = null;
		forget();
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
			for (int i : Menus.containerSlots(menu)) {
				ItemStack s = menu.getSlot(i).getItem();
				if (ItemInfo.isShulkerBox(s)) seen.put(i, s.copy());
			}
		}
		// Boxes out of the chest for the panels' handler keep their place, as they were in the chest.
		if (handler != null) for (var e : handler.boxes().entrySet()) seen.putIfAbsent(e.getKey(), e.getValue());
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
		// As many whole rows as the screen's height holds (MAX_ROWS at most): they're what shows, the rest a scroll away.
		int fit = Math.max(1, Math.min(MAX_ROWS, (int) Math.floor((sh - 2 * MARGIN + GAP) / step)));
		List<Float> sides = new ArrayList<>();
		for (int i = 0; ; i++) {
			float right = left + width + GAP + i * (pw + GAP), leftX = left - GAP - pw - i * (pw + GAP);
			boolean r = right + pw <= sw - MARGIN - BAR_WIDTH, l = leftX >= MARGIN;
			if (!r && !l) break;
			if (r) sides.add(right);
			if (l) sides.add(leftX);
		}
		if (sides.isEmpty()) return out;
		// The rows that show centred on the screen, beside the window: a few boxes sit level with it, not at the top.
		int shownRows = Math.min(fit, (seen.size() + sides.size() - 1) / sides.size());
		viewTop = (sh - (shownRows * step - GAP)) / 2;
		viewBottom = viewTop + shownRows * step - GAP;
		float first = viewTop;
		List<Integer> slots = new ArrayList<>(seen.keySet());
		int next = 0;
		for (int r = 0; next < slots.size(); r++) {
			float y = first + r * step;
			for (float x : sides) {
				if (next >= slots.size()) break;
				int slot = slots.get(next++);
				ItemStack box = seen.get(slot);
				List<ItemStack> held = handler == null ? null : handler.contents(slot);
				out.add(new Panel(box, held != null ? held : Tooltips.contents(box), slot, x, y, pw, ph, cell));
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
		boolean slotted = live && handler != null && handler.frame((AbstractContainerScreen<?>) screen, views(screen));
		if (panels.isEmpty()) return;
		ItemStack[] hovered = {null};
		// The box last used (its handler's lit one) keeps the window's outline; the rest go duller.
		int lit = handler == null ? -1 : handler.lit();
		Myriad.ui().draw(ctx, c -> {
			for (Panel p : panels) {
				float alpha = shown.get(p.slot()) * (going.contains(p.slot()) ? 0.45f : 1);
				if (alpha <= 0.01f) continue;
				c.push();
				if (alpha < 1) c.alpha(alpha);
				drawPanel(c, p, onPanel(p, mx, my) ? mx : Integer.MIN_VALUE, my, hovered, slotted, lit < 0 || lit == p.slot());
				c.pop();
			}
			drawScrollbar(c);
		});
		if (hovered[0] != null && menu.getCarried().isEmpty()) ctx.setTooltipForNextFrame(mc.font, hovered[0], mx, my);
	}

	/** The panels for their handler: each box's slots where they're drawn (as a slot's top left), showing where wholly in view. */
	private List<InventoryTweaks.GridPanel> views(InventoryTweaks.ScreenAccess screen) {
		List<InventoryTweaks.GridPanel> out = new ArrayList<>(panels.size());
		for (Panel p : panels) {
			boolean on = shown.getOrDefault(p.slot(), 0f) >= 0.5f;
			List<InventoryTweaks.GridCell> cells = new ArrayList<>(ROWS * COLS);
			for (int i = 0; i < ROWS * COLS; i++) {
				float sx = p.x() + PAD + (i % COLS) * p.cell() + (p.cell() - 16) / 2f;
				float sy = p.y() - scroll + HEADER + (i / COLS) * p.cell() + (p.cell() - 16) / 2f;
				cells.add(new InventoryTweaks.GridCell(Math.round(sx), Math.round(sy), on && sy >= viewTop && sy + 16 <= viewBottom));
			}
			out.add(new InventoryTweaks.GridPanel(p.slot(), p.box(), cells));
		}
		return out;
	}

	/** Whether {@code mx, my} is on a panel in view (clicks there aren't outside the window). */
	boolean covers(double mx, double my) {
		for (Panel p : panels) if (onPanel(p, mx, my)) return true;
		return false;
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
	private void drawPanel(Canvas c, Panel p, int mx, int my, ItemStack[] hovered, boolean slotted, boolean bright) {
		ThemeSettings theme = Myriad.ui().theme();
		float x = p.x(), y = p.y() - scroll, w = p.w(), h = p.h(), top = shownTop(p), bottom = shownBottom();
		float r = Math.min(theme.rounding.get(), 6);
		c.push();
		c.clip(x - 2, top, w + 4, bottom - top);
		c.backdrop(x, y, w, h, r, 1);
		c.roundRect(x, y, w, h, r, ColorUtil.withAlpha(theme.windowBackground.argb(), Math.max(200, ColorUtil.alpha(theme.windowBackground.argb()))));
		int from = theme.activeBorderFrom.argb(), to = theme.activeBorderTo.argb();
		if (!bright) {
			from = ColorUtil.withAlpha(from, ColorUtil.alpha(from) * 35 / 100);
			to = ColorUtil.withAlpha(to, ColorUtil.alpha(to) * 35 / 100);
		}
		c.gradientOutline(x, y, w, h, r, Math.max(1, theme.borderSize.get()), from, to, theme.borderAngle.get().floatValue());
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
			c.roundRect(sx + 0.5f, sy + 0.5f, p.cell() - 1, p.cell() - 1, 2, i == under && !slotted ? theme.surfaceHover.argb() : cell);
		}
		c.pop();
		// Their handler draws the boxes' items.
		if (slotted) return;
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

	/**
	 * A click at {@code mx, my} with {@code button}: on a box's slot with the chest open, the box goes down with a swap bar
	 * tab, or its panels' handler takes it (false: the click goes on to the screen); from a box on the swap bar or
	 * your inventory, the chest opens again. True if it was on a panel and goes no further.
	 */
	boolean press(InventoryTweaks.ScreenAccess screen, double mx, double my, int button) {
		AbstractContainerMenu menu = screen.essentials$menu();
		Boolean live = live(menu);
		if (live == null || chestAt == null) return false;
		for (Panel p : panels) {
			if (!onPanel(p, mx, my)) continue;
			if (live) {
				if (p.slotAt(mx, my, scroll) < 0) return true;
				if (take(screen)) return false;
				Containers.View view = chest(menu);
				if (button == 0 && view != null && !handlerHas(p.slot())) owner.putDownFromGrid(view, p.slot());
				return true;
			}
			if (!menu.getCarried().isEmpty() || !going.add(p.slot())) return true;
			int slot = p.slot();
			Myriad.containers().open(owner, chestAt, OPEN_TICKS, Placement.Options.forServer()).whenComplete((v, t) -> going.remove(slot));
			return true;
		}
		return false;
	}

	/** Whether the panels' handler takes a press on a box's slot. */
	private boolean take(InventoryTweaks.ScreenAccess screen) {
		return handler != null && handler.take((AbstractContainerScreen<?>) screen, views(screen));
	}

	/** Whether box {@code box} is out of the chest for the panels' handler. */
	private boolean handlerHas(int box) {
		return handler != null && handler.boxes().containsKey(box);
	}

	/**
	 * The wheel at {@code mx, my}: off the window, with more rows than show, it scrolls a row; over a panel it's always
	 * the page's (never Scroll Move on a box's items), true there even with nothing to scroll.
	 */
	boolean scrolled(InventoryTweaks.ScreenAccess screen, double mx, double my, double amount) {
		if (panels.isEmpty() || amount == 0) return false;
		if (lastRow <= 0) return covers(mx, my);
		int[] r = screen.essentials$bounds();
		if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return false;
		row = Math.max(0, Math.min(lastRow, Math.min(row, lastRow) + (amount < 0 ? 1 : -1)));
		return true;
	}
}
