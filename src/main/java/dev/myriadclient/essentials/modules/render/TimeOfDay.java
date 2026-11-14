package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.IntSetting;

/**
 * Locks the time of day you see: sky, sun, moon, stars and daylight. Only your view changes; the server's time (mob
 * spawning, beds) is untouched. The day count is kept, so the moon goes through its phases as usual. See this addon's
 * ClientClockInstanceMixin.
 */
public class TimeOfDay extends Module {
	/** The overworld's clock instance; ClientClockInstanceMixin tells it from the others by this. */
	public static Object overworldClock;

	/** Ticks in a Minecraft day. */
	private static final int DAY = 24000;

	private final IntSetting time = sgGeneral.integer("Time")
		.description("Ticks into the day: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight.").defaultValue(6000).range(0, DAY - 1).build();

	public TimeOfDay() {
		super(Tags.RENDER, "Time of Day", "Sets the time of day you see.");
	}

	/** {@code ticks} (the overworld clock's) moved to the chosen time on the same day. */
	public long lock(long ticks) {
		return Math.floorDiv(ticks, DAY) * DAY + time.get();
	}
}
