package dev.myriadclient.essentials.util;

import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.widget.RegistryPicker;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A choice among ids (an enchantment, a profession) shown by name and picked from a searchable list: saved and typed as
 * the id ({@code minecraft:mending}, or {@code mending}), shown as what the game calls it ({@code Mending}).
 */
public class NamedChoiceSetting extends ChoiceSetting {
	private final Function<String, String> names;

	public NamedChoiceSetting(String name, @Nullable String description, String defaultValue, @Nullable Supplier<Boolean> visible,
			Supplier<List<String>> options, Function<String, String> names) {
		super(name, description, defaultValue, visible, options);
		this.names = names;
	}

	/** What {@code option} is called on screen. */
	public String name(String option) {
		return names.apply(option);
	}

	/** An id's translated name, its key made by {@code key} ({@code enchantment.minecraft.mending}), else its path. */
	public static Function<String, String> translated(Function<Identifier, String> key) {
		return option -> {
			Identifier id = option.contains(":") ? Identifier.tryParse(option) : null;
			if (id == null) return option;
			String k = key.apply(id);
			if (Language.getInstance().has(k)) return Language.getInstance().getOrDefault(k);
			String path = id.getPath().replace('_', ' ');
			return path.isEmpty() ? option : path.substring(0, 1).toUpperCase(Locale.ROOT) + path.substring(1);
		};
	}

	@Override
	public boolean parse(String input) {
		String in = input.trim();
		for (String o : options()) {
			if (o.equalsIgnoreCase(in) || o.equalsIgnoreCase("minecraft:" + in) || name(o).equalsIgnoreCase(in)) {
				set(o);
				return true;
			}
		}
		return false;
	}

	@Override
	public String valueString() {
		return name(get());
	}

	/** Shows these settings as a searchable list of names. */
	public static void register(SettingWidgets widgets) {
		widgets.register(NamedChoiceSetting.class, s -> new RegistryPicker<>(new RegistryPicker.Source<String>() {
			@Override
			public Iterable<String> all() {
				return s.options();
			}

			@Override
			public boolean accepts(String t) {
				return true;
			}

			@Override
			public boolean contains(String t) {
				return t.equals(s.get());
			}

			@Override
			public void toggle(String t) {
				s.set(t);
			}

			@Override
			public int size() {
				return 1;
			}

			@Override
			public String label() {
				return s.name(s.get());
			}

			@Override
			public String id(String t) {
				return t;
			}

			@Override
			public String name(String t) {
				return s.name(t);
			}
		}), true);
	}
}
