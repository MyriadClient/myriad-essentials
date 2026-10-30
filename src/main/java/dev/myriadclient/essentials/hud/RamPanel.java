package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.TextHudPanel;

/** The game's memory in use, against what it may use at most ("RAM 1843 / 4096 MB"), as F3 counts it. */
public final class RamPanel extends TextHudPanel {
	public enum Show {
		USED_AND_MAX, PERCENT, BOTH
	}

	/** How often the figure is read again: steadier than every frame. */
	private static final long SAMPLE_MS = 500;

	private final EnumSetting<Show> show = sgGeneral.oneOf("Show", Show.USED_AND_MAX).description("Megabytes in use and the most, a percentage, or both.").build();

	private long sampledAt;
	private long used, max;

	public RamPanel() {
		super("RAM", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		long now = System.currentTimeMillis();
		if (now - sampledAt >= SAMPLE_MS) {
			Runtime rt = Runtime.getRuntime();
			used = (rt.totalMemory() - rt.freeMemory()) >> 20;
			max = rt.maxMemory() >> 20;
			sampledAt = now;
		}
		String mb = used + " / " + max + " MB", percent = used * 100 / Math.max(1, max) + "%";
		out.add("RAM", switch (show.get()) {
			case USED_AND_MAX -> mb;
			case PERCENT -> percent;
			case BOTH -> percent + " " + mb;
		});
	}
}
