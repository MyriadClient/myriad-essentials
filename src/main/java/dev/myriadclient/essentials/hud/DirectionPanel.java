package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Which way you're facing, with the axis it runs along ("North -Z"), and if you like your yaw and pitch. */
public final class DirectionPanel extends TextHudPanel {
	private static final String[] CARDINAL = {"South", "West", "North", "East"};
	private static final String[] CARDINAL_AXIS = {"+Z", "-X", "-Z", "+X"};
	private static final String[] EIGHT = {"South", "South West", "West", "North West", "North", "North East", "East", "South East"};
	private static final String[] EIGHT_AXIS = {"+Z", "-X +Z", "-X", "-X -Z", "-Z", "+X -Z", "+X", "+X +Z"};

	private final BoolSetting interCardinal = sgGeneral.bool("Intercardinal").description("Include North East, South West and so on.").build();
	private final BoolSetting axis = sgGeneral.bool("Axis").description("Show the coordinate direction (-Z, +X…).").defaultValue(true).build();
	private final BoolSetting showYaw = sgGeneral.bool("Yaw").description("Show your yaw in degrees, as F3 does.").build();
	private final BoolSetting showPitch = sgGeneral.bool("Pitch").description("Show your pitch in degrees, looking up negative.").build();

	public DirectionPanel() {
		super("Direction", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		float yaw = preview() ? 180 : Mth.wrapDegrees(mc.player.getYRot());
		// Yaw 0 faces south (+Z) and turns clockwise through west.
		float turned = (yaw + 360) % 360;
		String name, ax;
		if (interCardinal.get()) {
			int i = Math.round(turned / 45f) % 8;
			name = EIGHT[i];
			ax = EIGHT_AXIS[i];
		} else {
			int i = Math.round(turned / 90f) % 4;
			name = CARDINAL[i];
			ax = CARDINAL_AXIS[i];
		}
		float pitch = preview() ? 0 : mc.player.getXRot();
		List<String> parts = new ArrayList<>(6);
		parts.add(axis.get() ? name + " " : name);
		parts.add(axis.get() ? ax : "");
		if (showYaw.get()) Collections.addAll(parts, "  Yaw ", String.format(Locale.ROOT, "%.1f", yaw));
		if (showPitch.get()) Collections.addAll(parts, "  Pitch ", String.format(Locale.ROOT, "%.1f", pitch));
		out.parts(parts.toArray(String[]::new));
	}
}
