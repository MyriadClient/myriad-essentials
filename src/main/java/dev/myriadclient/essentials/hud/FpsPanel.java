package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;

/** Frames per second. */
public final class FpsPanel extends TextHudPanel {
	public FpsPanel() {
		super("FPS", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		out.add("FPS", String.valueOf(mc.getFps()));
	}
}
