package dev.myriadclient.essentials.effects;

import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.BackdropEffect;
import net.minecraft.resources.Identifier;

/**
 * The background effects Essentials adds (Theme, then Background: Style): film grain and halftone dither. Each is a
 * shader in {@code assets/myriad-essentials/shaders/effects/}, which reads these options in the order they're built,
 * each as 0 to 1 across its range; keep the two in step.
 */
public final class BackgroundEffects {
	private BackgroundEffects() {
	}

	public static void register() {
		BackdropEffect.register(id("grain"), "Grain", o -> {
			o.decimal("Amount").description("How far each speck lightens or darkens the background.").defaultValue(0.06).range(0.01, 0.3).decimals(2).build();
			o.integer("Size").description("Each speck's size, in pixels.").defaultValue(1).range(1, 6).build();
			o.bool("Moving").description("Grain that moves, like film.").defaultValue(false).build();
		});
		BackdropEffect.register(id("dither"), "Dither", o -> {
			o.color("Color").description("The dots' colour; their strength is its alpha.")
				.defaultValue(SettingColor.role(SettingColor.Mode.SECONDARY, 0xB0)).build();
			o.decimal("Amount").description("How much of the background the wisps cover.").defaultValue(0.6).range(0.1, 1).decimals(2).build();
			o.decimal("Size").description("Each dot's size, in pixels.").defaultValue(1).range(1, 4).decimals(1).build();
			o.decimal("Scale").description("How big the wisps are.").defaultValue(1).range(0.5, 3).decimals(1).build();
			o.bool("Drift").description("The wisps drift slowly.").defaultValue(true).build();
		});
	}

	private static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath("myriad-essentials", "effects/" + name);
	}
}
