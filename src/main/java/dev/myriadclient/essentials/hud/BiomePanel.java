package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** The biome you're standing in, by the name the game gives it. */
public final class BiomePanel extends TextHudPanel {
	public BiomePanel() {
		super("Biome", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		if (preview()) {
			out.add("Biome", "Plains");
			return;
		}
		String name = mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
			.map(key -> Component.translatable(Util.makeDescriptionId("biome", key.identifier())).getString()).orElse("?");
		out.add("Biome", name);
	}
}
