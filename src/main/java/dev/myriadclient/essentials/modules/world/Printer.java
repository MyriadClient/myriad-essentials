package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.Render3DEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.job.Steps;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.BoxStyle;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.BuildingSettings;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.TickCached;
import dev.myriadclient.essentials.litematica.LitematicaBlueprint;
import dev.myriadclient.essentials.util.BuildPlanRenderer;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the schematics placed in Litematica around you as you move: the layers Litematica shows, nearest and lowest
 * first. Every block comes out the way the schematic has it, facing, half, axis, hinge and all, from a click worked
 * out by simulating the placement, with the look turned to it first where the server checks. Repeaters,
 * comparators, note blocks, doors, trapdoors, gates and levers are then clicked into the schematic's setting; candles,
 * sea pickles, snow layers and double slabs get as many as it has. Blocks are moved to the hotbar as they're needed.
 * It runs as a job ({@code Steps.keepUp}), so the Jobs window and HUD show what it's doing and what it's waiting for.
 */
public class Printer extends Module {
	private final BuildingSettings building = new BuildingSettings(sgGeneral);
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Look at each block's face to place it, and only click faces you "
		+ "can see, as servers that check where you look want (current Grim). Off (2b2t), any face in reach is clicked, and the look only "
		+ "turns for blocks that face a way.").defaultValue(true).build();
	private final BoolSetting clicks = sgGeneral.bool("Click Settings").description("After placing, click repeaters, comparators, note blocks, "
		+ "doors, trapdoors, gates and levers into the schematic's setting.").defaultValue(true).build();

	private final SettingGroup sgRender = settings.group("Render");
	private final BoolSetting showPlan = sgRender.bool("Show Plan").description("Outline what's about to be placed, what's waiting, and what's stuck.")
		.defaultValue(true).build();
	private final BoxStyle style = new BoxStyle(sgRender, showPlan::get);

	private @Nullable Job<Void> job;
	private Building.Options running;
	/** The build behind the job, found once a tick: the plan drawn every frame and the count in the module list read it. */
	private final TickCached<@Nullable Build> build = TickCached.of(this::findBuild);

	public Printer() {
		super(Tags.WORLD, "Printer", "Builds Litematica schematics around you.");
	}

	private Building.Options options() {
		Building.Options o = building.options();
		// Faces you can see matter only where the look has to be on them.
		return o.withKeepUp(true).withPlacing(o.placing().withRotate(rotate.get()).withVisibleFaces(rotate.get())).withClickSettings(clicks.get());
	}

	@Override
	protected void onEnable() {
		if (!FabricLoader.getInstance().isModLoaded("litematica")) {
			error("Litematica isn't installed.");
			disable();
			return;
		}
		start();
	}

	/** Builds the schematic as Litematica shows it (read live), with the settings as they are now. */
	private void start() {
		running = options();
		Job<Void> mine = job = Myriad.jobs().start(this, Steps.keepUp(new LitematicaBlueprint(building.breakWrong::get), running).named("Printing"));
		// A job that ended on its own (the build was stopped, or you left the world) turns the module off with it.
		mine.result().whenComplete((v, t) -> {
			if (isEnabled() && job == mine) disable();
		});
	}

	/** A changed setting takes effect at once: the build starts over with it. */
	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		if (job != null && !options().equals(running)) {
			Job<Void> old = job;
			job = null;
			old.cancel();
			start();
		}
	}

	@Override
	protected void onDisable() {
		Job<Void> j = job;
		job = null;
		if (j != null) j.cancel();
	}

	/** The running build, or null while off (and for the tick the job takes to start it). */
	public @Nullable Build build() {
		return build.get();
	}

	/** The build whose owner is a step of this module's job. */
	private @Nullable Build findBuild() {
		Job<Void> mine = job;
		if (mine == null) return null;
		for (Build b : Myriad.building().running()) {
			if (b.owner() instanceof Job<?> j && root(j) == mine) return b;
		}
		return null;
	}

	private static Job<?> root(Job<?> j) {
		while (j.parent() != null) j = j.parent();
		return j;
	}

	@Override
	public String hudInfo() {
		Build b = build();
		if (b == null) return null;
		long left = b.steps().stream().filter(s -> s.status() != Build.Status.DONE).count();
		return left == 0 ? null : Long.toString(left);
	}

	@Subscribe
	private void onRender(Render3DEvent e) {
		if (showPlan.get()) BuildPlanRenderer.draw(e, build(), style);
	}
}
