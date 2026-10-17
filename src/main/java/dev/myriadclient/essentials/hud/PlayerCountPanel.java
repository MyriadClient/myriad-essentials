package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;

/** How many players are on the server (the tab list). */
public final class PlayerCountPanel extends TextHudPanel {
	public PlayerCountPanel() {
		super("Player Count", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		int n = mc.getConnection() == null ? 1 : mc.getConnection().getOnlinePlayers().size();
		out.add("Players", String.valueOf(n));
	}
}
