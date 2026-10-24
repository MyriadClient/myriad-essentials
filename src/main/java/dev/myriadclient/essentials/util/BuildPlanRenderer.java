package dev.myriadclient.essentials.util;

import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.render.RenderLook;
import dev.myriadclient.api.setting.SettingColor;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a build's plan in a module's look, in the render mode: what's about to happen in the accent colour, what's
 * waiting on the server in yellow, breaks in red, and what's stuck in magenta. Done positions, and ones out of reach,
 * aren't shown. Each is a little inside its block, so neighbours stay apart.
 */
public final class BuildPlanRenderer {
	private BuildPlanRenderer() {
	}

	public static void draw(RenderEvent e, @Nullable Build build, RenderLook look) {
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
			if (color != 0) e.box(new AABB(step.pos()).deflate(0.05), look, color);
		}
	}
}
