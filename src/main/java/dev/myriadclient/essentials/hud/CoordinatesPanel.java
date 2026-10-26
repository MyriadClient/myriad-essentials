package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Where you are, and the matching Nether or Overworld coordinates. */
public final class CoordinatesPanel extends TextHudPanel {
	/** Between the numbers: two spaces, so each stands apart at a glance with its digits grouped. */
	private static final String GAP = "  ";

	private final BoolSetting otherDimension = sgGeneral.bool("Other Dimension").description("Show the matching Nether/Overworld coordinates.").defaultValue(true).build();
	private final BoolSetting decimals = sgGeneral.bool("Decimals").description("Show one decimal place.").build();
	private final BoolSetting commas = sgGeneral.bool("Commas").description("Group the digits in thousands: 8,290,779.").defaultValue(true).build();

	public CoordinatesPanel() {
		super("Coordinates", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		if (preview()) {
			out.add("XYZ", "0 0 0");
			return;
		}
		var p = mc.player;
		List<String> parts = new ArrayList<>(List.of("XYZ ", String.join(GAP, number(p.getX()), number(p.getY()), number(p.getZ()))));
		if (otherDimension.get() && mc.level.dimension() != Level.END) {
			boolean nether = mc.level.dimension() == Level.NETHER;
			double k = nether ? 8 : 1 / 8.0;
			parts.addAll(List.of(nether ? "  [OW " : "  [Nether ", number(p.getX() * k) + GAP + number(p.getZ() * k), "]", ""));
		}
		out.parts(parts.toArray(String[]::new));
	}

	private String number(double v) {
		return String.format(Locale.ROOT, (commas.get() ? "%," : "%") + (decimals.get() ? ".1f" : ".0f"), v);
	}
}
