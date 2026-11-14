package dev.myriadclient.essentials.modules.render.tab;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.essentials.modules.render.ExtraTab;
import net.minecraft.Optionull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Everything the list shows, worked out and placed at most once a tick, so that drawing a frame only walks what's here:
 * sorting, names, widths and layout are what made the player list expensive to draw every frame.
 */
final class TabModel {
	private static final float MARGIN = 10, PAD = 4, COLUMN_GAP = 6, HEAD = 8, HEAD_GAP = 2, SCORE_GAP = 4, BARS = 10, PING_GAP = 3;
	/** A column isn't made narrower than this to fit more of them, if its contents would need the room. */
	private static final float MIN_COLUMN = 92;
	private static final int SPECTATOR_ALPHA = 0x90;
	private static final int[] BAR_STEPS = {1000, 600, 300, 150};

	/** Minecraft's order: by the server's list order, spectators after players, then team and name. */
	private static final Comparator<PlayerInfo> SERVER_ORDER = Comparator.<PlayerInfo>comparingInt(p -> -p.getTabListOrder())
		.thenComparingInt(p -> p.getGameMode() == GameType.SPECTATOR ? 1 : 0)
		.thenComparing(p -> Optionull.mapOrDefault(p.getTeam(), PlayerTeam::getName, ""))
		.thenComparing(p -> p.getProfile().name(), String::compareToIgnoreCase);
	private static final Comparator<PlayerInfo> BY_NAME = Comparator.comparing(p -> p.getProfile().name(), String::compareToIgnoreCase);
	/** Lowest first, not-yet-known last. */
	private static final Comparator<PlayerInfo> BY_PING = Comparator.comparingInt(p -> p.getLatency() < 0 ? Integer.MAX_VALUE : p.getLatency());

	/** A line of text in its colours, measured. */
	static final class Text {
		final String[] parts;
		final int[] colors;
		final float[] offsets;
		final float width;

		private Text(List<String> parts, List<Integer> colors, Canvas c, FontFamily font, float size) {
			this.parts = parts.toArray(new String[0]);
			this.colors = new int[this.parts.length];
			this.offsets = new float[this.parts.length];
			float x = 0;
			for (int i = 0; i < this.parts.length; i++) {
				this.colors[i] = colors.get(i);
				this.offsets[i] = x;
				x += c.textWidth(font, size, this.parts[i]);
			}
			this.width = x;
		}
	}

	static final class Line {
		final Text text;
		final float x, y;

		private Line(Text text, float x, float y) {
			this.text = text;
			this.x = x;
			this.y = y;
		}
	}

	static final class Row {
		float x, y, w, textY, headX, nameX, scoreX, pingX, barsX;
		int fill, tint, pingColor, bars;
		Text name;
		@Nullable Text score;
		@Nullable String ping;
		HeadAtlas.@Nullable Cell cell;
		@Nullable Identifier skin;
		boolean showHead, hat, flip;
	}

	private record Ranked(PlayerInfo info, int rank) {
	}

	FontFamily font;
	float fontSize, rowHeight;
	float panelX, panelY, panelW, panelH, radius;
	int panelColor, borderFrom, borderTo, separator;
	float borderAngle;
	Row[] rows;
	/** The rows whose skin isn't in the atlas, next to those sharing a texture. */
	Row[] unbaked;
	Line[] lines;
	float[] separators;

	static TabModel build(ExtraTab m, Canvas c, HeadAtlas heads, int screenW, int screenH, Scoreboard scoreboard, @Nullable Objective objective,
	                      @Nullable Component header, @Nullable Component footer) {
		TabModel model = new TabModel();
		ThemeSettings theme = Myriad.ui().theme();
		boolean themed = theme.themedScreens.get();
		model.font = c.defaultFont();
		model.fontSize = c.defaultFontSize();
		float textH = c.textHeight(model.font, model.fontSize);
		model.rowHeight = Math.max(textH, HEAD + 1);
		int textColor = themed ? theme.text.argb() : 0xFFFFFFFF;
		model.panelColor = themed ? theme.windowBackground.argb() : 0x80000000;
		model.radius = themed ? theme.rounding.get() : 0;
		model.borderFrom = themed ? theme.activeBorderFrom.argb() : 0;
		model.borderTo = themed ? theme.activeBorderTo.argb() : 0;
		model.borderAngle = theme.borderAngle.getFloat();
		model.separator = themed ? theme.inactiveBorder.argb() : 0;
		int stripe = themed ? ColorUtil.withAlpha(theme.surface.argb(), 0x50) : 0x20FFFFFF;

		List<PlayerInfo> players = sorted(m);
		int shown = players.size();
		var connection = Minecraft.getInstance().getConnection();
		boolean showHeads = m.heads.get() && connection != null && connection.onlineMode();
		boolean showScores = m.scoreboard.get() && objective != null;
		float headW = showHeads ? HEAD + HEAD_GAP : 0;

		Row[] rows = new Row[shown];
		float nameW = 0, scoreW = 0, pingW = 0;
		for (int i = 0; i < shown; i++) {
			PlayerInfo info = players.get(i);
			Row r = rows[i] = new Row();
			boolean spectator = info.getGameMode() == GameType.SPECTATOR;
			int highlight = highlight(m, info);
			int nameColor = highlight != 0 ? highlight : textColor;
			if (spectator) nameColor = ColorUtil.withAlpha(nameColor, SPECTATOR_ALPHA);
			r.tint = spectator ? ColorUtil.withAlpha(0xFFFFFFFF, SPECTATOR_ALPHA) : 0xFFFFFFFF;
			Text name = lines(displayName(info), nameColor, c, model.font, model.fontSize).getFirst();
			r.name = highlight != 0 ? recolored(name, nameColor) : name;
			nameW = Math.max(nameW, r.name.width);
			r.fill = highlight != 0 ? ColorUtil.withAlpha(highlight, 0x30) : 0;
			if (showScores && !spectator) {
				r.score = score(scoreboard, objective, info, c, model);
				if (r.score != null) scoreW = Math.max(scoreW, r.score.width);
			}
			switch (m.ping.get()) {
				case OFF -> {
				}
				case BARS -> {
					r.bars = bars(info.getLatency());
					r.pingColor = ExtraTab.pingColor(info.getLatency());
					pingW = BARS;
				}
				case NUMBER -> {
					r.ping = ExtraTab.pingText(info.getLatency());
					r.pingColor = ExtraTab.pingColor(info.getLatency());
					pingW = Math.max(pingW, c.textWidth(model.font, model.fontSize, r.ping));
				}
			}
			if (showHeads) {
				r.showHead = true;
				r.hat = info.showHat();
				r.skin = info.getSkin().body().texturePath();
				r.cell = heads.cell(r.skin, r.hat);
				Player entity = Minecraft.getInstance().level.getPlayerByUUID(info.getProfile().id());
				r.flip = entity != null && AvatarRenderer.isPlayerUpsideDown(entity);
			}
		}
		heads.upload();

		float availW = screenW - 2 * MARGIN - 2 * PAD;
		List<Text> headerLines = m.headerFooter.get() && header != null ? fitted(lines(header, textColor, c, model.font, model.fontSize), availW, c, model) : List.of();
		List<Text> footerLines = m.headerFooter.get() && footer != null ? fitted(lines(footer, textColor, c, model.font, model.fontSize), availW, c, model) : List.of();
		float headerH = headerLines.isEmpty() ? 0 : headerLines.size() * textH + PAD;
		float footerH = footerLines.isEmpty() ? 0 : footerLines.size() * textH + PAD;
		float availH = screenH - 2 * MARGIN - 2 * PAD - headerH - footerH - textH;

		float scoreRoom = scoreW > 0 ? scoreW + SCORE_GAP : 0, pingRoom = pingW > 0 ? pingW + PING_GAP : 0;
		float natural = headW + nameW + scoreRoom + pingRoom + 1;
		float least = Math.min(natural, MIN_COLUMN);
		int maxCols = Math.max(1, (int) ((availW + COLUMN_GAP) / (least + COLUMN_GAP)));
		int maxRows = Math.max(1, (int) (availH / model.rowHeight));
		int perColumn = Math.min(m.rows.get(), maxRows);
		int cols = Math.max(1, ceil(shown, perColumn));
		if (cols > maxCols) {
			perColumn = Math.min(maxRows, ceil(shown, maxCols));
			cols = Math.min(maxCols, ceil(shown, perColumn));
		}
		int visible = Math.min(shown, perColumn * cols);
		int hidden = shown - visible;
		int rowCount = Math.max(1, ceil(visible, cols));
		float slot = Math.min(natural, (availW - (cols - 1) * COLUMN_GAP) / cols);

		float gridW = cols * slot + (cols - 1) * COLUMN_GAP;
		float widest = gridW;
		for (Text t : headerLines) widest = Math.max(widest, t.width);
		for (Text t : footerLines) widest = Math.max(widest, t.width);
		float moreH = hidden > 0 ? textH : 0;
		model.panelW = widest + 2 * PAD;
		model.panelH = headerH + rowCount * model.rowHeight + moreH + footerH + 2 * PAD;
		model.panelX = (screenW - model.panelW) / 2;
		model.panelY = MARGIN;
		float gridX = model.panelX + PAD + (widest - gridW) / 2;
		float gridY = model.panelY + PAD + headerH;

		float textOffset = (model.rowHeight - textH) / 2;
		float nameRoom = slot - headW - scoreRoom - pingRoom - 1;
		model.rows = new Row[visible];
		for (int i = 0; i < visible; i++) {
			Row r = model.rows[i] = rows[i];
			int col = i / rowCount, row = i % rowCount;
			r.x = gridX + col * (slot + COLUMN_GAP);
			r.y = gridY + row * model.rowHeight;
			r.w = slot;
			r.textY = r.y + textOffset;
			r.headX = r.x + 1;
			r.nameX = r.x + headW + 1;
			r.name = fit(r.name, nameRoom, c, model);
			if (r.fill == 0 && (row & 1) == 1) r.fill = stripe;
			float right = r.x + slot - 1;
			if (r.ping != null) r.pingX = right - c.textWidth(model.font, model.fontSize, r.ping);
			r.barsX = right - BARS;
			if (r.score != null) r.scoreX = right - pingRoom - r.score.width;
		}
		List<Row> unbaked = new ArrayList<>();
		for (Row r : model.rows) if (r.showHead && r.cell == null) unbaked.add(r);
		unbaked.sort(Comparator.comparing(r -> r.skin));
		model.unbaked = unbaked.toArray(new Row[0]);

		List<Line> out = new ArrayList<>();
		float centre = screenW / 2f, y = model.panelY + PAD;
		for (Text t : headerLines) {
			out.add(new Line(t, centre - t.width / 2, y));
			y += textH;
		}
		float gridBottom = gridY + rowCount * model.rowHeight;
		if (hidden > 0) {
			Text more = lines(Component.literal("+" + hidden + " more"), themed ? theme.textDim.argb() : 0xFFAAAAAA, c, model.font, model.fontSize).getFirst();
			out.add(new Line(more, centre - more.width / 2, gridBottom));
		}
		y = gridBottom + moreH + (footerLines.isEmpty() ? 0 : PAD);
		for (Text t : footerLines) {
			out.add(new Line(t, centre - t.width / 2, y));
			y += textH;
		}
		model.lines = out.toArray(new Line[0]);
		model.separators = new float[(headerLines.isEmpty() ? 0 : 1) + (footerLines.isEmpty() ? 0 : 1)];
		int sep = 0;
		if (!headerLines.isEmpty()) model.separators[sep++] = gridY - PAD / 2 - 0.5f;
		if (!footerLines.isEmpty()) model.separators[sep] = gridBottom + moreH + PAD / 2 - 0.5f;
		return model;
	}

	private static List<PlayerInfo> sorted(ExtraTab m) {
		var connection = Minecraft.getInstance().getConnection();
		if (connection == null) return List.of();
		var listed = connection.getListedOnlinePlayers();
		int limit = m.players.get();
		if (m.sort.get() == ExtraTab.Sort.FRIENDS_FIRST) {
			List<Ranked> ranked = new ArrayList<>(listed.size());
			for (PlayerInfo info : listed) ranked.add(new Ranked(info, rank(info)));
			ranked.sort(Comparator.comparingInt(Ranked::rank).thenComparing(Ranked::info, SERVER_ORDER));
			List<PlayerInfo> out = new ArrayList<>(Math.min(limit, ranked.size()));
			for (int i = 0; i < ranked.size() && i < limit; i++) out.add(ranked.get(i).info());
			return out;
		}
		List<PlayerInfo> all = new ArrayList<>(listed);
		all.sort(switch (m.sort.get()) {
			case NAME -> BY_NAME;
			case PING -> BY_PING.thenComparing(BY_NAME);
			default -> SERVER_ORDER;
		});
		return all.size() > limit ? all.subList(0, limit) : all;
	}

	/** You, then friends, then enemies, then everyone else. */
	private static int rank(PlayerInfo info) {
		var player = Minecraft.getInstance().player;
		String name = info.getProfile().name();
		if (player != null && info.getProfile().id().equals(player.getUUID())) return 0;
		if (Myriad.friends().isFriend(name)) return 1;
		return Myriad.enemies().isEnemy(name) ? 2 : 3;
	}

	/** The colour picking this player out, 0 for none; someone on both lists is a friend. */
	private static int highlight(ExtraTab m, PlayerInfo info) {
		var player = Minecraft.getInstance().player;
		String name = info.getProfile().name();
		if (m.you.get() && player != null && info.getProfile().id().equals(player.getUUID())) return m.youColor.argb();
		boolean friend = Myriad.friends().isFriend(name);
		if (m.friends.get() && friend) return m.friendColor.argb();
		return m.enemies.get() && !friend && Myriad.enemies().isEnemy(name) ? m.enemyColor.argb() : 0;
	}

	private static Component displayName(PlayerInfo info) {
		Component custom = info.getTabListDisplayName();
		if (custom != null) return custom;
		MutableComponent name = Component.literal(info.getProfile().name());
		return PlayerTeam.formatNameForTeam(info.getTeam(), name);
	}

	private static @Nullable Text score(Scoreboard scoreboard, Objective objective, PlayerInfo info, Canvas c, TabModel model) {
		ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(ScoreHolder.fromGameProfile(info.getProfile()), objective);
		if (score == null) return null;
		Component text = objective.getRenderType() == ObjectiveCriteria.RenderType.HEARTS
			? Component.literal(String.valueOf(score.value())).withColor(0xFF5555)
			: score.formatValue(objective.numberFormatOrDefault(StyledFormat.PLAYER_LIST_DEFAULT));
		return lines(text, 0xFFFFFF55, c, model.font, model.fontSize).getFirst();
	}

	private static int bars(int latency) {
		if (latency < 0) return 0;
		int bars = 1;
		for (int step : BAR_STEPS) if (latency < step) bars++;
		return bars;
	}

	private static int ceil(int a, int b) {
		return (a + b - 1) / b;
	}

	/** {@code component}'s lines, each in runs of one colour, {@code color} where it sets none. */
	private static List<Text> lines(Component component, int color, Canvas c, FontFamily font, float size) {
		List<Text> out = new ArrayList<>();
		List<String> parts = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		StringBuilder run = new StringBuilder();
		int[] runColor = {color};
		StringDecomposer.iterateFormatted(component, Style.EMPTY, (position, style, codePoint) -> {
			if (codePoint == '\n') {
				endRun(run, parts, colors, runColor[0]);
				out.add(new Text(parts, colors, c, font, size));
				parts.clear();
				colors.clear();
				return true;
			}
			int now = style.getColor() != null ? (color & 0xFF000000) | style.getColor().getValue() : color;
			if (now != runColor[0]) {
				endRun(run, parts, colors, runColor[0]);
				runColor[0] = now;
			}
			run.appendCodePoint(codePoint);
			return true;
		});
		endRun(run, parts, colors, runColor[0]);
		out.add(new Text(parts, colors, c, font, size));
		return out;
	}

	private static void endRun(StringBuilder run, List<String> parts, List<Integer> colors, int color) {
		if (run.isEmpty()) return;
		parts.add(run.toString());
		colors.add(color);
		run.setLength(0);
	}

	/** {@code text}, all of it in {@code color}. */
	private static Text recolored(Text text, int color) {
		Arrays.fill(text.colors, color);
		return text;
	}

	private static List<Text> fitted(List<Text> lines, float width, Canvas c, TabModel model) {
		List<Text> out = new ArrayList<>(lines.size());
		for (Text t : lines) out.add(fit(t, width, c, model));
		return out;
	}

	/** {@code text} cut short with an ellipsis if it's wider than {@code width}. */
	private static Text fit(Text text, float width, Canvas c, TabModel model) {
		if (text.width <= width) return text;
		List<String> parts = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		for (int i = 0; i < text.parts.length; i++) {
			float room = width - text.offsets[i];
			if (room <= 0) break;
			float partW = (i + 1 < text.parts.length ? text.offsets[i + 1] : text.width) - text.offsets[i];
			parts.add(partW <= room ? text.parts[i] : c.ellipsize(model.font, model.fontSize, text.parts[i], room));
			colors.add(text.colors[i]);
			if (partW > room) break;
		}
		return new Text(parts, colors, c, model.font, model.fontSize);
	}
}
