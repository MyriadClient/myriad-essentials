package dev.myriadclient.essentials.util;

import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Edits a {@link HiddenPosListSetting} of containers: a row for each (what's there and how far, its × taking it off),
 * then a row adding the one you look at. The coordinates only show in a row's tooltip, so a glance at the menu doesn't
 * give them away.
 */
final class ContainerListEditor extends Widget {
	private static final float GAP = 2;
	private final HiddenPosListSetting setting;
	/** The row under the mouse last frame (-1 none; the list's size: the add row), and whether on its ×. */
	private int hovered = -1;
	private boolean onRemove;

	ContainerListEditor(HiddenPosListSetting setting) {
		this.setting = setting;
	}

	@Override
	protected float measure(Canvas c, float width) {
		return (setting.positions().size() + 1) * (ROW + GAP);
	}

	/** What's at {@code pos} as you see it, or why that isn't known. */
	private static String what(BlockPos pos) {
		var level = Minecraft.getInstance().level;
		if (level == null || !level.isLoaded(pos)) return "Not loaded";
		BlockState state = level.getBlockState(pos);
		return state.isAir() ? "Nothing there now" : state.getBlock().getName().getString();
	}

	private static String distance(BlockPos pos) {
		var player = Minecraft.getInstance().player;
		if (player == null) return "";
		double d = Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(pos)));
		return d >= 1000 ? String.format(Locale.ROOT, "%.1f km", d / 1000) : Math.round(d) + " m";
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		List<BlockPos> list = setting.positions();
		hovered = -1;
		onRemove = false;
		var level = Minecraft.getInstance().level;
		float ty = (ROW - c.textHeight()) / 2;
		for (int i = 0; i <= list.size(); i++) {
			float ry = y + i * (ROW + GAP);
			boolean hover = mx >= x && mx < x + width && my >= ry && my < ry + ROW;
			if (hover) hovered = i;
			if (i == list.size()) {
				// The add row: quiet till it's hovered.
				if (hover) c.roundRect(x, ry, width, ROW, 4, theme().surfaceHover.argb());
				String add = Icons.PLUS + "  Add what you're looking at";
				add = c.ellipsize(c.defaultFont(), c.defaultFontSize(), add, width - 8);
				c.text(add, x + 4, ry + ty, hover ? theme().text.argb() : theme().textDim.argb());
				continue;
			}
			BlockPos pos = list.get(i);
			boolean remove = hover && mx >= x + width - ROW;
			if (remove) onRemove = true;
			c.roundRect(x, ry, width, ROW, 4, hover && !remove ? theme().surfaceHover.argb() : theme().surface.argb());
			boolean shulker = level != null && level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock;
			c.text(shulker ? Icons.ARCHIVE : Icons.INBOX, x + 4, ry + ty, theme().textDim.argb());
			String far = distance(pos);
			float farW = c.textWidth(far);
			float nameX = x + 4 + ROW;
			String name = c.ellipsize(c.defaultFont(), c.defaultFontSize(), what(pos), Math.max(0, x + width - ROW - farW - 8 - nameX));
			c.text(name, nameX, ry + ty, theme().text.argb());
			c.text(far, x + width - ROW - farW - 4, ry + ty, theme().textDim.argb());
			int red = theme().red.argb();
			if (remove) c.roundRect(x + width - ROW, ry, ROW, ROW, 4, ColorUtil.withAlpha(red, 45));
			c.text(Icons.TIMES, x + width - ROW + (ROW - c.textWidth(Icons.TIMES)) / 2, ry + ty, remove ? red : theme().textDim.argb());
		}
		if (hovered >= 0 && hovered < list.size()) {
			BlockPos pos = list.get(hovered);
			tooltip = onRemove ? "Take it off" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
		} else {
			tooltip = hovered == list.size() ? "Or use the bind, looking at it" : null;
		}
		if (hovered >= 0) offerTooltip(mx, my);
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false;
		List<BlockPos> list = setting.positions();
		int row = (int) ((my - y) / (ROW + GAP));
		if (row == list.size()) {
			setting.addLookedAt();
			return true;
		}
		if (row >= 0 && row < list.size() && mx >= x + width - ROW) {
			setting.remove(list.get(row));
			return true;
		}
		return true;
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		setting.addLookedAt();
	}
}
