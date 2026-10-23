package dev.myriadclient.essentials.util;

import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.render.BoxStyle;
import dev.myriadclient.api.setting.SettingColor;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a build's plan in a module's {@link BoxStyle}: what's about to happen in the accent colour, what's waiting on
 * the server in yellow, breaks in red, and what's stuck in magenta. Done positions, and ones out of reach, aren't drawn.
 */
public final class BuildPlanRenderer {
	private BuildPlanRenderer() {
	}

	public static void draw(Render3DEvent e, @Nullable Build build, BoxStyle style) {
		if (build == null || !build.isRunning()) return;
		int todo = SettingColor.role(SettingColor.Mode.ACCENT).argb();
		int waiting = SettingColor.role(SettingColor.Mode.YELLOW).argb();
		int breaking = SettingColor.role(SettingColor.Mode.RED).argb();
		int stuck = SettingColor.role(SettingColor.Mode.MAGENTA).argb();
		for (Build.Step step : build.steps()) {
			int color = switch (step.status()) {
				case DONE, OUT_OF_RANGE -> 0;
				case PLACE -> todo;
				case WAITING -> waiting;
				case BREAK -> breaking;
				default -> stuck;
			};
			if (color != 0) style.draw(e.shapes(), new AABB(step.pos()).deflate(0.05), color, 1);
		}
	}
}
