package dev.myriadclient.essentials.util;

import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.StringSetting;
import dev.myriadclient.api.ui.SettingWidgets;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Containers' positions ("x y z; x y z") kept out of sight: the settings show a row for each with what's there and how
 * far (the coordinates only in its tooltip), each with × to take it off, and a row adding the one you look at (whatever
 * the module's {@code add} does). Never in shared configs, and commands print how many rather than the numbers. A
 * single position saved before reads as a list of one.
 */
public class HiddenPosListSetting extends StringSetting {
	private final Runnable add;

	public HiddenPosListSetting(String name, @Nullable String description, String defaultValue, @Nullable Supplier<Boolean> visible, Runnable add) {
		super(name, description, defaultValue, visible);
		this.add = add;
	}

	/** A protected one (see {@link Setting#isProtected}), its add row running {@code add}; add it with {@code group.add}. */
	public static HiddenPosListSetting of(String name, String description, Runnable add) {
		return new Builder(name, add).description(description).protect().build();
	}

	/** Adds the container you look at (the module's own way, with its messages). */
	void addLookedAt() {
		add.run();
	}

	/** The value {@link #parsed} was read from (they're asked for every frame while outlined). */
	private @Nullable String parsedFrom;
	private List<BlockPos> parsed = List.of();

	/** The positions, in the order they were added (any that don't read as one left out), as a new list. */
	public List<BlockPos> positions() {
		String value = get();
		if (!value.equals(parsedFrom)) {
			parsed = read(value);
			parsedFrom = value;
		}
		return new ArrayList<>(parsed);
	}

	private static List<BlockPos> read(String value) {
		List<BlockPos> out = new ArrayList<>();
		for (String part : value.split(";")) {
			String[] xyz = part.trim().split("[ ,]+");
			if (xyz.length != 3) continue;
			try {
				BlockPos p = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
				if (!out.contains(p)) out.add(p);
			} catch (NumberFormatException ignored) {
			}
		}
		return out;
	}

	public boolean contains(BlockPos pos) {
		return positions().contains(pos);
	}

	/** Adds {@code pos} at the end; false if it's there already. */
	public boolean add(BlockPos pos) {
		List<BlockPos> list = positions();
		if (list.contains(pos)) return false;
		list.add(pos.immutable());
		write(list);
		return true;
	}

	/** Removes {@code pos}; false if it wasn't there. */
	public boolean remove(BlockPos pos) {
		List<BlockPos> list = positions();
		if (!list.remove(pos)) return false;
		write(list);
		return true;
	}

	private void write(List<BlockPos> list) {
		List<String> parts = new ArrayList<>();
		for (BlockPos p : list) parts.add(p.getX() + " " + p.getY() + " " + p.getZ());
		set(String.join("; ", parts));
	}

	@Override
	public String valueString() {
		int n = positions().size();
		return n == 0 ? "none" : n + " set";
	}

	/** Shows these settings as a list of containers (see the class comment). */
	public static void register(SettingWidgets widgets) {
		widgets.register(HiddenPosListSetting.class, ContainerListEditor::new, true);
	}

	private static final class Builder extends Setting.Builder<Builder, String, HiddenPosListSetting> {
		private final Runnable add;

		Builder(String name, Runnable add) {
			super(name, "");
			this.add = add;
		}

		@Override
		protected HiddenPosListSetting create() {
			return new HiddenPosListSetting(name, description, defaultValue, visible, add);
		}
	}
}
