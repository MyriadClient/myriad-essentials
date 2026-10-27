package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.GameReadyEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.StringListSetting;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Chat as you want it: in the theme's font and colours, filtered, repeats stacked, more of it kept. What Minecraft's own
 * Chat Settings have (size, width, heights, opacities, line spacing, what's shown) is kept in step with them while it's
 * on, each tick: a change on either side is copied to the other, so they're the same setting seen in two places. The
 * rest is the module's own, read by the chat mixins (ChatComponentMixin, ChatBackgroundMixin, ChatTextShadowMixin).
 * Off, chat is Minecraft's.
 * <p>
 * It was part of Myriad core's theme preferences until core 1.3: the first time it loads, it takes what you had set
 * there, and turns on, so chat looks as it did.
 */
public class Chat extends Module {
	/** A server's chat line: {@code <name> message}. */
	private static final Pattern CHAT_LINE = Pattern.compile("^<[^<>\\s]{1,32}> ");
	/** Ticks a change is saved to Minecraft's options after (a slider dragged changes it every frame). */
	private static final int SAVE_TICKS = 20;

	public enum Font {
		MINECRAFT, THEME
	}

	public enum Background {
		THEME, MINECRAFT, NONE
	}

	public enum Shown {
		ALL, WHILE_OPEN, COMMANDS_ONLY, HIDDEN
	}

	private final SettingGroup sgLook = settings.group("Look");
	public final EnumSetting<Font> font = sgLook.oneOf("Font", Font.THEME).description("Theme: the theme's font. Minecraft: its own.")
		.onChanged(v -> rescale()).build();
	public final DoubleSetting size = sgLook.decimal("Size").description("How large chat's text is.").defaultValue(1.0).range(0, 1).decimals(2).build();
	public final IntSetting width = sgLook.integer("Width").description("In GUI pixels.").defaultValue(320).range(40, 320).build();
	public final IntSetting height = sgLook.integer("Height").description("While chat is open, in GUI pixels.").defaultValue(180).range(20, 180).build();
	public final IntSetting heightClosed = sgLook.integer("Height Closed").description("While chat is closed, in GUI pixels.").defaultValue(90).range(20, 180).build();
	public final DoubleSetting textOpacity = sgLook.decimal("Text Opacity").defaultValue(1.0).range(0, 1).decimals(2).build();
	public final DoubleSetting backgroundOpacity = sgLook.decimal("Background Opacity").defaultValue(0.5).range(0, 1).decimals(2).build();
	public final DoubleSetting lineSpacing = sgLook.decimal("Line Spacing").defaultValue(0.0).range(0, 1).decimals(2).build();
	public final EnumSetting<Background> background = sgLook.oneOf("Background", Background.THEME)
		.description("What chat's lines are drawn on: the theme's window colour, Minecraft's black, or nothing.").build();
	public final BoolSetting shadow = sgLook.bool("Text Shadow").defaultValue(true).build();

	private final SettingGroup sgMessages = settings.group("Messages");
	public final EnumSetting<Shown> shown = sgMessages.oneOf("Shown", Shown.ALL).description("All messages; all, only while chat is open; "
		+ "only commands' answers (no player chat, also a server's own <name> lines, as 2b2t sends chat); or none.").onChanged(v -> rescale()).build();
	public final StringListSetting filters = sgMessages.stringList("Filters")
		.description("Messages containing any of these are hidden (a pattern in /slashes/ is a regular expression; case doesn't matter).")
		.onChanged(v -> rescale()).build();
	public final BoolSetting stackRepeats = sgMessages.bool("Stack Repeats").description("The same message again shows once, with (x2), (x3).")
		.defaultValue(true).build();
	public final IntSetting history = sgMessages.integer("History").description("Messages kept to scroll back through (Minecraft keeps 100).")
		.defaultValue(1000).range(100, 10000).build();
	/** Whether what core's theme preferences had has been taken (see the class comment). */
	private final BoolSetting carriedOver = sgMessages.bool("Carried Over").visible(() -> false).build();

	/** Chat is drawing (ChatComponentMixin): its text shadow and background follow the settings. */
	public static boolean drawing;

	private final List<Link<?>> links = new ArrayList<>();
	/** Each link's value here when last in step, to tell which side changed. */
	private Object[] last = new Object[0];
	private @Nullable Shown shownLast;
	private int saveIn = -1;
	private @Nullable List<String> filterSource;
	private List<Pattern> patterns = List.of();

	public Chat() {
		super(Tags.RENDER, "Chat", "Chat in the theme's font and colours, filtered, repeats stacked, more kept; Minecraft's Chat Settings here too.");
		Myriad.events().subscribe(new CarryOver());
	}

	@Override
	protected void onEnable() {
		last = new Object[links.size()];
		shownLast = null;
		rescale();
	}

	@Override
	protected void onDisable() {
		drawing = false;
		rescale();
	}

	/** A setting here and the Minecraft option it is, with the value as each holds it. */
	private record Link<T>(Supplier<T> mine, Consumer<T> setMine, Function<Options, OptionInstance<Double>> option,
	                       Function<T, Double> toOption, Function<Double, T> fromOption) {
		/** True if they differed and were brought back in step: {@code last} says which changed since. */
		boolean sync(Options o, Object[] last, int i) {
			T mine = mine().get();
			double theirs = option().apply(o).get();
			if (last[i] == null) last[i] = mine;
			if (!mine.equals(last[i])) {
				// Changed here: to Minecraft's.
				option().apply(o).set(toOption().apply(mine));
				last[i] = mine;
				return true;
			}
			T fromTheirs = fromOption().apply(theirs);
			if (!fromTheirs.equals(mine)) {
				// Changed in Minecraft's own settings (or on start): to here.
				setMine().accept(fromTheirs);
				last[i] = mine().get();
			}
			return false;
		}
	}

	private void link() {
		links.add(new Link<>(size::get, size::set, Options::chatScale, v -> v, v -> v));
		links.add(pixels(width, Options::chatWidth, 280, 40));
		links.add(pixels(height, Options::chatHeightFocused, 160, 20));
		links.add(pixels(heightClosed, Options::chatHeightUnfocused, 160, 20));
		links.add(fraction(textOpacity, Options::chatOpacity));
		links.add(fraction(backgroundOpacity, Options::textBackgroundOpacity));
		links.add(fraction(lineSpacing, Options::chatLineSpacing));
		last = new Object[links.size()];
	}

	private static Link<Double> fraction(DoubleSetting s, Function<Options, OptionInstance<Double>> option) {
		return new Link<>(s::get, s::set, option, v -> v, v -> Math.round(v * 100) / 100.0);
	}

	/** Minecraft keeps widths and heights as a fraction: {@code span} pixels over {@code min}. */
	private static Link<Integer> pixels(IntSetting s, Function<Options, OptionInstance<Double>> option, int span, int min) {
		return new Link<>(s::get, s::set, option, px -> (px - min) / (double) span, f -> (int) Math.round(f * span + min));
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (mc.options == null) return;
		if (links.isEmpty()) link();
		boolean changed = false;
		for (int i = 0; i < links.size(); i++) changed |= links.get(i).sync(mc.options, last, i);
		changed |= syncShown(mc.options);
		if (changed) saveIn = SAVE_TICKS;
		if (saveIn >= 0 && saveIn-- == 0) mc.options.save();
	}

	private boolean syncShown(Options o) {
		Shown mine = shown.get();
		Shown theirs = switch (o.chatVisibility().get()) {
			case FULL -> Shown.ALL;
			case SYSTEM -> Shown.COMMANDS_ONLY;
			case HIDDEN -> Shown.HIDDEN;
		};
		if (shownLast == null) shownLast = mine;
		if (mine != shownLast) {
			o.chatVisibility().set(switch (mine) {
				case ALL, WHILE_OPEN -> ChatVisiblity.FULL;
				case COMMANDS_ONLY -> ChatVisiblity.SYSTEM;
				case HIDDEN -> ChatVisiblity.HIDDEN;
			});
			shownLast = mine;
			return true;
		}
		// Shown only while open is all of them to Minecraft.
		if (theirs != mine && !(mine == Shown.WHILE_OPEN && theirs == Shown.ALL)) {
			shown.set(theirs);
			shownLast = theirs;
		}
		return false;
	}

	/** Chat's lines are split by the font and width they're drawn at, and shown by the filters: split again. */
	private static void rescale() {
		if (mc.gui != null) mc.gui.hud.getChat().rescaleChat();
	}

	/**
	 * What core's theme preferences had, taken once (core 1.3 left them there, hidden and unused), and the module turned
	 * on: chat looked like this before, with nothing to turn on.
	 */
	private final class CarryOver {
		@Subscribe
		@SuppressWarnings({"deprecation", "removal"})
		private void onReady(GameReadyEvent e) {
			if (carriedOver.get()) return;
			ThemeSettings t = Myriad.ui().theme();
			font.set(t.chatFont.get() == ThemeSettings.ChatFont.MINECRAFT ? Font.MINECRAFT : Font.THEME);
			size.set(t.chatSize.get());
			width.set(t.chatWidth.get());
			height.set(t.chatHeight.get());
			heightClosed.set(t.chatHeightUnfocused.get());
			textOpacity.set(t.chatTextOpacity.get());
			backgroundOpacity.set(t.chatBackgroundOpacity.get());
			lineSpacing.set(t.chatLineSpacing.get());
			background.set(switch (t.chatBackground.get()) {
				case THEME -> Background.THEME;
				case VANILLA -> Background.MINECRAFT;
				case NONE -> Background.NONE;
			});
			shadow.set(t.chatShadow.get());
			shown.set(Shown.valueOf(t.chatShown.get().name()));
			filters.set(new ArrayList<>(t.chatFilters.get()));
			stackRepeats.set(t.chatStackRepeats.get());
			history.set(t.chatHistory.get());
			carriedOver.set(true);
			setEnabled(true);
		}
	}

	// ---- read by the chat mixins ------------------------------------------------------------------------------------

	/** The module while it's on, else null. */
	public static @Nullable Chat on() {
		Chat m = Modules.get(Chat.class);
		return m != null && m.isEnabled() ? m : null;
	}

	/** Whether chat closed shows nothing (Shown: While Open). */
	public static boolean hiddenClosed() {
		Chat m = on();
		return m != null && m.shown.get() == Shown.WHILE_OPEN;
	}

	/** Whether chat's text has its shadow. */
	public static boolean shadowed() {
		Chat m = on();
		return m == null || m.shadow.get();
	}

	/** The colour for one of chat's black line backgrounds ({@code color}): the theme's window colour, black, or none (0). */
	public static int background(int color) {
		Chat m = on();
		if (m == null || (color & 0xFFFFFF) != 0) return color;
		return switch (m.background.get()) {
			case MINECRAFT -> color;
			case NONE -> 0;
			case THEME -> ColorUtil.withAlpha(Myriad.ui().theme().windowBackground.argb(), ColorUtil.alpha(color));
		};
	}

	/**
	 * Whether {@code message} is shown: none of the filters is in it, and with only commands' answers shown, it's no
	 * player's chat (Minecraft hides signed chat then, but a server like 2b2t sends chat as its own lines).
	 */
	public static boolean shown(GuiMessage message) {
		Chat m = on();
		if (m == null) return true;
		String text = message.content().getString();
		if (m.shown.get() == Shown.COMMANDS_ONLY && (message.source() == GuiMessageSource.PLAYER || CHAT_LINE.matcher(text).find())) return false;
		List<Pattern> patterns = m.patterns();
		for (Pattern p : patterns) if (p.matcher(text).find()) return false;
		return true;
	}

	/** The filters as patterns, compiled again when they change: /a regex/, else the words as they are; any case. */
	private List<Pattern> patterns() {
		List<String> source = filters.get();
		// The list is changed in place, so it's compared by what's in it.
		if (!source.equals(filterSource)) {
			filterSource = List.copyOf(source);
			List<Pattern> compiled = new ArrayList<>();
			for (String f : source) {
				String s = f.strip();
				if (s.isEmpty()) continue;
				try {
					compiled.add(s.length() > 2 && s.startsWith("/") && s.endsWith("/")
						? Pattern.compile(s.substring(1, s.length() - 1), Pattern.CASE_INSENSITIVE)
						: Pattern.compile(Pattern.quote(s.toLowerCase(Locale.ROOT)), Pattern.CASE_INSENSITIVE));
				} catch (PatternSyntaxException ignored) {
					// A pattern that isn't one filters nothing.
				}
			}
			patterns = compiled;
		}
		return patterns;
	}

	/** How many messages chat keeps. */
	public static int kept() {
		Chat m = on();
		return m != null ? m.history.get() : 100;
	}

	/** Whether the same message again is stacked onto the last. */
	public static boolean stacks() {
		Chat m = on();
		return m != null && m.stackRepeats.get();
	}

	/** Whether chat is in the theme's font. */
	public static boolean themeFont() {
		Chat m = on();
		return m != null && m.font.get() == Font.THEME;
	}

	/**
	 * How tall chat's rows are, before Line Spacing: Minecraft's 9, or 11 in the theme's font, whose ascenders and
	 * descenders (7.5 above the baseline and 2.5 below, at GUI scale 2) don't fit in 9. Only accents on capitals reach
	 * into the row above.
	 */
	public static int rowHeight() {
		return themeFont() ? 11 : 9;
	}
}
