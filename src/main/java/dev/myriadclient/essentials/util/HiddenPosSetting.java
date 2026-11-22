package dev.myriadclient.essentials.util;

import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.StringSetting;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A block's position ("x y z") kept out of sight: the settings show dots, not the coordinates, so opening the menu (on
 * a stream, say) doesn't give them away. Show reveals them till the settings close; Set takes it from what you look at
 * (whatever the module's {@code set} does). Never in shared configs, and commands print "set" rather than the numbers.
 */
public class HiddenPosSetting extends StringSetting {
	private final Runnable set;

	public HiddenPosSetting(String name, @Nullable String description, String defaultValue, @Nullable Supplier<Boolean> visible, Runnable set) {
		super(name, description, defaultValue, visible);
		this.set = set;
	}

	/** A protected one (see {@link Setting#isProtected}), its row's Set running {@code set}; add it with {@code group.add}. */
	public static HiddenPosSetting of(String name, String description, Runnable set) {
		return new Builder(name, set).description(description).protect().build();
	}

	/** The position, or null when none is set (or it doesn't read as one). */
	public @Nullable BlockPos pos() {
		String[] parts = get().trim().split("[ ,]+");
		if (parts.length != 3) return null;
		try {
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Sets the position. */
	public void set(BlockPos p) {
		set(p.getX() + " " + p.getY() + " " + p.getZ());
	}

	@Override
	public String valueString() {
		return pos() == null ? "not set" : "set";
	}

	/** Shows these settings as dots, with Show and Set. */
	public static void register(SettingWidgets widgets) {
		widgets.register(HiddenPosSetting.class, s -> {
			boolean[] shown = {false};
			HBox row = new HBox(3);
			row.addWeighted(new Label(() -> s.pos() == null ? "Not set" : shown[0] ? s.get() : "••••••••"), 1);
			row.addFixed(new Button(() -> shown[0] ? "Hide" : "Show", () -> shown[0] = !shown[0]), 34).tooltip("Show the coordinates till you close this");
			row.addFixed(new Button("Set", s.set), 28).tooltip("The container you look at");
			return row;
		}, false);
	}

	private static final class Builder extends Setting.Builder<Builder, String, HiddenPosSetting> {
		private final Runnable set;

		Builder(String name, Runnable set) {
			super(name, "");
			this.set = set;
		}

		@Override
		protected HiddenPosSetting create() {
			return new HiddenPosSetting(name, description, defaultValue, visible, set);
		}
	}
}
