package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.IntSetting;

/**
 * Locks the time of day you see: sky, sun, moon, stars and daylight. Only your view changes; the server's time (mob
 * spawning, beds) is untouched. The day count is kept, so the moon goes through its phases as usual. See this addon's
 * ClientClockManagerMixin.
 */
public class TimeOfDay extends Module {
	/** Ticks in a Minecraft day. */
	private static final int DAY = 24000;

	private final IntSetting time = sgGeneral.intSetting("Time")
		.description("Ticks into the day: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight.").defaultValue(6000).range(0, DAY - 1).build();

	public TimeOfDay() {
		super(Tags.RENDER, "Time of Day", "Set the time of day you see.");
	}

	/** {@code ticks} (the overworld clock's) moved to the chosen time on the same day. */
	public long lock(long ticks) {
		return Math.floorDiv(ticks, DAY) * DAY + time.get();
	}
}
