package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.Baritone;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.awt.Color;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Baritone's settings in Myriad's menu, made from Baritone's own list (so new ones show up by themselves), sorted into
 * groups. While it's on, every setting changed here from Baritone's default is set in Baritone, for now: Baritone's
 * settings file isn't touched, and turning it off puts Baritone's own values back (unless something else changed them
 * since, like a {@code #set}). Settings left at Baritone's default leave Baritone's own value alone. Kept per profile
 * like any module's, so control panels and shared configs can carry a Baritone setup. Only registered with Baritone
 * installed.
 */
public class BaritoneSettings extends Module {
	/** Groups by the first rule a setting's name matches, in this order; the rest go to Pathing. */
	private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

	static {
		GROUPS.put("Breaking & Placing", List.of("allowbreak", "allowplace", "blocksto", "avoidbreaking", "throwaway", "autotool", "reach",
			"blockbreakspeed", "rightclickspeed", "itemsaver", "silktouch", "sword", "walkwhilebreaking", "inventory", "penalty"));
		GROUPS.put("Movement", List.of("allow", "assume", "sprint", "parkour", "overshoot", "maxfall", "walkon", "vines", "potion",
			"enterportal", "avoidance", "strictliquid"));
		GROUPS.put("Mining", List.of("mine", "mining", "exposedores", "explore"));
		GROUPS.put("Building", List.of("build", "schematic", "layer", "mapart", "okif", "incorrect", "correctblock", "breakfromabove"));
		GROUPS.put("Farming", List.of("farm", "replant"));
		GROUPS.put("Following", List.of("follow"));
		GROUPS.put("Looking", List.of("look", "anticheat"));
		GROUPS.put("Elytra", List.of("elytra"));
		GROUPS.put("Rendering", List.of("render", "color", "opacity", "linewidth", "fadepath", "selection", "ylevelbox"));
		GROUPS.put("Chat", List.of("chat", "prefix", "echo", "censor", "logas", "verbose", "notification", "messagetag"));
	}

	/** One setting here and the Baritone setting it stands for. */
	private record Link(String name, Setting<?> setting, Function<Object, Object> toBaritone, boolean asText) {
	}

	private final List<Link> links = new ArrayList<>();
	/** Baritone's own values for the settings set while on, to put back; and what was set, to tell if it changed since. */
	private final Map<String, Object> before = new HashMap<>(), set = new HashMap<>();

	public BaritoneSettings(List<Baritone.SettingInfo> baritone) {
		super(Tags.WORLD, "Baritone", "Baritone's settings: while on, what you change here from Baritone's defaults is set in Baritone.");
		Map<String, SettingGroup> groups = new LinkedHashMap<>();
		for (String g : GROUPS.keySet()) groups.put(g, settings.group(g));
		SettingGroup pathing = settings.group("Pathing");
		for (Baritone.SettingInfo info : baritone) {
			Link link = link(info, groups.getOrDefault(groupOf(info.name()), pathing));
			if (link == null) continue;
			links.add(link);
			link.setting().onChanged(v -> {
				if (isEnabled()) apply(link);
			});
		}
	}

	private static String groupOf(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (var g : GROUPS.entrySet()) for (String part : g.getValue()) if (lower.contains(part)) return g.getKey();
		return "Pathing";
	}

	/** "allowBreak" as "Allow Break". */
	private static String title(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (i == 0) sb.append(Character.toUpperCase(c));
			else if (Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) sb.append(' ').append(c);
			else sb.append(c);
		}
		return sb.toString();
	}

	/** This module's setting for one of Baritone's, as near its type as Myriad has; null if it has no default to show. */
	@SuppressWarnings("unchecked")
	private static Link link(Baritone.SettingInfo info, SettingGroup group) {
		String title = title(info.name()), about = "Baritone's " + info.name() + ".";
		Object def = info.defaultValue();
		Class<?> type = info.valueClass();
		if (type == Boolean.class) {
			return new Link(info.name(), group.bool(title).description(about).defaultValue((Boolean) def).build(), v -> v, false);
		}
		if (type == Integer.class) {
			return new Link(info.name(), group.integer(title).description(about).defaultValue((Integer) def).typed().build(), v -> v, false);
		}
		if (type == Double.class || type == Float.class) {
			boolean f = type == Float.class;
			return new Link(info.name(), group.decimal(title).description(about).defaultValue(((Number) def).doubleValue()).decimals(3).typed().build(),
				v -> f ? (Object) ((Number) v).floatValue() : v, false);
		}
		if (type == Color.class) {
			return new Link(info.name(), group.color(title).description(about).defaultValue(SettingColor.of(((Color) def).getRGB())).build(),
				v -> new Color(((SettingColor) v).argb(), true), false);
		}
		Class<?> element = listOf(info.genericType());
		if (element == Block.class) {
			return new Link(info.name(), group.blocks(title).description(about).defaultValue(new LinkedHashSet<>((Collection<Block>) def)).build(),
				v -> new ArrayList<>((Collection<?>) v), false);
		}
		if (element == Item.class) {
			return new Link(info.name(), group.items(title).description(about).defaultValue(new LinkedHashSet<>((Collection<Item>) def)).build(),
				v -> new ArrayList<>((Collection<?>) v), false);
		}
		// Anything else (large numbers, block maps, coordinates, rotations) as the text Baritone's #set takes.
		String text = Baritone.settingDefaultText(info.name());
		if (text == null) return null;
		return new Link(info.name(), group.string(title).description(about + " Written as Baritone's #set takes it.").defaultValue(text).build(), v -> v, true);
	}

	/** The element type of a {@code List<X>}, or null. */
	private static Class<?> listOf(Type type) {
		if (type instanceof ParameterizedType p && p.getRawType() == List.class && p.getActualTypeArguments()[0] instanceof Class<?> c) return c;
		return null;
	}

	@Override
	protected void onEnable() {
		for (Link link : links) apply(link);
	}

	@Override
	protected void onDisable() {
		for (Link link : links) restore(link);
	}

	/** Sets {@code link}'s value in Baritone if it differs from Baritone's default; back to Baritone's own if it doesn't. */
	private void apply(Link link) {
		Object value = link.setting().get();
		if (Objects.equals(value, link.setting().defaultValue())) {
			restore(link);
			return;
		}
		Object current = current(link);
		if (!before.containsKey(link.name())) before.put(link.name(), current);
		Object wanted = link.toBaritone().apply(value);
		boolean done = link.asText() ? Baritone.setSettingText(link.name(), (String) wanted) : Baritone.setSetting(link.name(), wanted) != null;
		if (done) set.put(link.name(), current(link));
	}

	/** Puts Baritone's own value back, unless something changed the setting since this set it. */
	private void restore(Link link) {
		if (!before.containsKey(link.name())) return;
		Object own = before.remove(link.name()), ours = set.remove(link.name());
		if (!Objects.equals(current(link), ours)) return;
		if (link.asText()) Baritone.setSettingText(link.name(), (String) own);
		else Baritone.setSetting(link.name(), own);
	}

	/** Baritone's value now: its text for text settings, a copy for lists (Baritone's own list may be changed in place). */
	private static Object current(Link link) {
		if (link.asText()) return Baritone.settingText(link.name());
		Object v = Baritone.getSetting(link.name());
		return v instanceof List<?> list ? new ArrayList<>(list) : v;
	}
}
