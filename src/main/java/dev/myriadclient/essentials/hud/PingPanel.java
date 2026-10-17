package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;

/** Your ping to the server, in milliseconds. */
public final class PingPanel extends TextHudPanel {
	public PingPanel() {
		super("Ping", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		out.add("Ping", (preview() ? 50 : Myriad.server().ping()) + " ms");
	}
}
