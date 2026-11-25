package dev.myriadclient.essentials.util;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.KeyEvent;
import dev.myriadclient.api.event.events.MouseButtonEvent;
import dev.myriadclient.api.event.events.Render2DEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.render.WorldLabel;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A module's input and output containers (chests, barrels, hoppers, shulker boxes: anything with slots): kept as
 * {@link HiddenPosListSetting}s, added by looking at one and adding it in the settings (each row's × takes it off), or
 * with a bind (Input Bind or Output Bind: on a container that isn't one, makes it one; on one that is, takes it off), and outlined and
 * labelled in the world while the module runs (and for a few seconds after a change, so you see what you picked). The
 * binds work with the module off, so they can be set up first. {@link ContainersCommand} does the same from chat, a
 * macro or a wheel.
 */
public final class ContainerLinks {
	/** How long the outlines stay after a change with the module off, in ms. */
	private static final long SHOW_AFTER_CHANGE_MS = 5000;
	/** Every module's, by its name without spaces ({@code AutoCraft}), for {@link ContainersCommand}. */
	private static final Map<String, ContainerLinks> BY_MODULE = new LinkedHashMap<>();

	private final Minecraft mc = Minecraft.getInstance();
	private final Module module;
	private final String inputName, outputName;
	public final HiddenPosListSetting inputs, outputs;
	private final KeybindSetting inputBind, outputBind;
	private final BoolSetting show;
	private final ColorSetting inputColor, outputColor;
	private final RenderSettings look;
	private long changedAt;

	/**
	 * Builds the settings: Inputs, Outputs and their binds in {@code general} (described by {@code inputWhat} and
	 * {@code outputWhat}), the outlines in {@code render}. {@code inputName} and {@code outputName} ("input", "output
	 * chest") are what messages call one.
	 */
	public ContainerLinks(Module module, SettingGroup general, SettingGroup render, String inputName, String outputName, String inputWhat, String outputWhat) {
		this.module = module;
		this.inputName = inputName;
		this.outputName = outputName;
		inputs = general.add(HiddenPosListSetting.of("Inputs", inputWhat + " Look at one and add it, or use Input Bind.", () -> pick(true, true)));
		outputs = general.add(HiddenPosListSetting.of("Outputs", outputWhat + " Look at one and add it, or use Output Bind.", () -> pick(false, true)));
		inputBind = general.keybind("Input Bind").description("Look at a container and press: it becomes an input, or stops being one.").build();
		outputBind = general.keybind("Output Bind").description("Look at a container and press: it becomes an output, or stops being one.").build();
		show = render.bool("Show Containers").description("Outline the inputs and outputs, labelled, while it runs.").defaultValue(true).build();
		inputColor = render.color("Input Color").defaultValue(SettingColor.role(SettingColor.Mode.GREEN)).visible(show::get).build();
		outputColor = render.color("Output Color").defaultValue(SettingColor.role(SettingColor.Mode.BLUE)).visible(show::get).build();
		look = RenderSettings.builder(render).visible(show::get).throughWalls(true).build();
		Myriad.events().subscribe(new Listener());
		BY_MODULE.put(module.name().replace(" ", ""), this);
	}

	/** Every module's, by its name without spaces. */
	static Map<String, ContainerLinks> byModule() {
		return Collections.unmodifiableMap(BY_MODULE);
	}

	Module module() {
		return module;
	}

	public List<BlockPos> inputs() {
		return inputs.positions();
	}

	public List<BlockPos> outputs() {
		return outputs.positions();
	}

	/** The container you look at, or null (with a warning). */
	private @Nullable BlockPos lookedAt() {
		if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
			|| !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof Container)) {
			module.warn("Look at a chest (or any container) first");
			return null;
		}
		return hit.getBlockPos().immutable();
	}

	/** The position {@code list} has for the container at {@code pos}: it, or the other half of its double chest; or null. */
	private @Nullable BlockPos listed(HiddenPosListSetting list, BlockPos pos) {
		for (BlockPos p : halves(pos)) if (list.contains(p)) return p;
		return null;
	}

	/** Add (or remove) the container you look at as an input or an output. */
	void pick(boolean input, boolean add) {
		BlockPos pos = lookedAt();
		if (pos == null) return;
		HiddenPosListSetting list = input ? inputs : outputs, other = input ? outputs : inputs;
		String name = input ? inputName : outputName;
		BlockPos have = listed(list, pos);
		if (add) {
			if (have != null) {
				module.info("That's an " + name + " already");
				return;
			}
			BlockPos was = listed(other, pos);
			if (was != null) other.remove(was);
			list.add(pos);
			module.info(capital(name) + " added (" + count(list.positions().size(), name) + ")");
		} else {
			if (have == null) {
				module.warn("That isn't an " + name);
				return;
			}
			list.remove(have);
			module.info(capital(name) + " removed (" + count(list.positions().size(), name) + ")");
		}
		changedAt = System.currentTimeMillis();
	}

	/** A bind: adds the container you look at, or removes it if it's there. */
	void toggle(boolean input) {
		if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			pick(input, true);
			return;
		}
		pick(input, listed(input ? inputs : outputs, hit.getBlockPos()) == null);
	}

	/** Takes off every input (or output). */
	void clear(boolean input) {
		HiddenPosListSetting list = input ? inputs : outputs;
		int n = list.positions().size();
		list.set("");
		module.info("Removed " + count(n, input ? inputName : outputName));
		changedAt = System.currentTimeMillis();
	}

	/** How many inputs and outputs, as a sentence (never where they are). */
	String summary() {
		return count(inputs.positions().size(), inputName) + ", " + count(outputs.positions().size(), outputName);
	}

	private static String count(int n, String name) {
		return n + " " + name + (n == 1 ? "" : "s");
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** The container at {@code pos}: both halves of a double chest. */
	public List<BlockPos> halves(BlockPos pos) {
		List<BlockPos> halves = new ArrayList<>(List.of(pos));
		if (mc.level == null) return halves;
		BlockState state = mc.level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
			halves.add(pos.relative(ChestBlock.getConnectedDirection(state)));
		return halves;
	}

	private boolean showing() {
		return show.get() && mc.level != null && (module.isEnabled() || System.currentTimeMillis() - changedAt < SHOW_AFTER_CHANGE_MS);
	}

	private void label(Render2DEvent e, BlockPos pos, String text, int color) {
		List<BlockPos> halves = halves(pos);
		double x = 0, z = 0;
		for (BlockPos p : halves) {
			x += p.getX() + 0.5;
			z += p.getZ() + 0.5;
		}
		Vec3 top = new Vec3(x / halves.size(), pos.getY() + 1.3, z / halves.size());
		WorldLabel.draw(e.canvas(), top, WorldLabel.distanceScale(top), List.of(new WorldLabel.Segment(text, 0xFFFFFFFF)), WorldLabel.Background.ROUNDED,
			0xAA000000, color, true);
	}

	/** The binds and the outlines, whether the module's on or not. */
	private final class Listener {
		@Subscribe
		private void onKey(KeyEvent e) {
			if (inputBind.wasPressed(e)) toggle(true);
			else if (outputBind.wasPressed(e)) toggle(false);
		}

		@Subscribe
		private void onMouse(MouseButtonEvent e) {
			if (inputBind.wasPressed(e)) toggle(true);
			else if (outputBind.wasPressed(e)) toggle(false);
		}

		@Subscribe
		private void onRender(RenderEvent e) {
			if (!showing()) return;
			for (BlockPos in : inputs()) for (BlockPos p : halves(in)) e.block(p, look, inputColor.argb());
			for (BlockPos out : outputs()) for (BlockPos p : halves(out)) e.block(p, look, outputColor.argb());
		}

		@Subscribe
		private void onLabels(Render2DEvent e) {
			if (!showing()) return;
			for (BlockPos in : inputs()) label(e, in, "Input", inputColor.argb());
			for (BlockPos out : outputs()) label(e, out, "Output", outputColor.argb());
		}
	}
}
