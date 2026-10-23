package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.HighlightSettings;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.Widget;

/**
 * First-person hand options: move, rotate and scale the held item and arm, stop the camera sway, render at full
 * brightness, or hide either hand (applied by this addon's HeldItemRendererMixin), and highlight your bare hand, the
 * items you hold, or both, with Myriad's shader highlights. Hand and items that look alike share one outline.
 */
public class ViewModel extends Module {

	public final BoolSetting brighten = sgGeneral.bool("Brighten").description("Draw the hands at full brightness.").build();
	public final BoolSetting noSway = sgGeneral.bool("No Sway").description("Stop the hands lagging behind camera movement.").build();
	public final BoolSetting hideMain = sgGeneral.bool("Hide Main Hand").build();
	public final BoolSetting hideOff = sgGeneral.bool("Hide Offhand").description("Handy for big shields and totems.").build();
	public final DoubleSetting swingSpeed = sgGeneral.decimal("Swing Speed").description("How fast your hands swing; 1 is vanilla (Haste and Mining Fatigue still count).")
		.defaultValue(1).range(0.25, 4).decimals(2).build();
	public final BoolSetting noSwitch = sgGeneral.bool("No Switch Animation").description("Show a new item straight away instead of lowering and raising the hand.").build();

	private final SettingGroup sgPosition = settings.group("Position");
	public final DoubleSetting x = sgPosition.decimal("X").description("Sideways; mirrored for the other hand.").defaultValue(0).range(-2, 2).build();
	public final DoubleSetting y = sgPosition.decimal("Y").defaultValue(0).range(-2, 2).build();
	public final DoubleSetting z = sgPosition.decimal("Z").defaultValue(0).range(-2, 2).build();
	public final BoolSetting separateOffhand = sgPosition.bool("Separate Offhand").description("Position the off hand on its own instead of mirroring the main hand.").build();
	public final DoubleSetting offX = sgPosition.decimal("Offhand X").description("Sideways, outwards from the body.").defaultValue(0).range(-2, 2).visible(separateOffhand::get).build();
	public final DoubleSetting offY = sgPosition.decimal("Offhand Y").defaultValue(0).range(-2, 2).visible(separateOffhand::get).build();
	public final DoubleSetting offZ = sgPosition.decimal("Offhand Z").defaultValue(0).range(-2, 2).visible(separateOffhand::get).build();

	private final SettingGroup sgRotation = settings.group("Rotation");
	public final IntSetting rotX = sgRotation.integer("Pitch").defaultValue(0).range(-180, 180).build();
	public final IntSetting rotY = sgRotation.integer("Yaw").description("Mirrored for the other hand.").defaultValue(0).range(-180, 180).build();
	public final IntSetting rotZ = sgRotation.integer("Roll").description("Mirrored for the other hand.").defaultValue(0).range(-180, 180).build();

	private final SettingGroup sgScale = settings.group("Scale");
	public final DoubleSetting scale = sgScale.decimal("Scale").defaultValue(1).range(0.1, 3).build();
	public final DoubleSetting offhandScale = sgScale.decimal("Offhand Scale").defaultValue(1).range(0.1, 3).build();

	private final SettingGroup sgHandHighlight = settings.group("Hand Highlight");
	private final BoolSetting handHighlight = sgHandHighlight.bool("Enabled").description("Highlight your bare hand (vanilla only shows it while your main hand is empty).")
		.onChanged(v -> listenForHighlights()).build();
	private final ColorSetting handColor = sgHandHighlight.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(handHighlight::get).build();
	private final HighlightSettings handStyle = HighlightSettings.builder(sgHandHighlight).visible(handHighlight::get).noThroughWalls().build();

	private final SettingGroup sgItemHighlight = settings.group("Item Highlight");
	private final BoolSetting itemHighlight = sgItemHighlight.bool("Enabled").description("Highlight the items in your hands.")
		.onChanged(v -> listenForHighlights()).build();
	private final ColorSetting itemColor = sgItemHighlight.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(itemHighlight::get).build();
	private final HighlightSettings itemStyle = HighlightSettings.builder(sgItemHighlight).visible(itemHighlight::get).noThroughWalls().build();

	/**
	 * Listens for hand highlights only while one is on: while anything listens for highlights, Myriad draws vanilla's
	 * glowing entities itself, which shouldn't change just because View Model is on.
	 */
	private final HandHighlights highlights = new HandHighlights();
	private boolean listening;

	public ViewModel() {
		super(Tags.RENDER, "View Model", "Move, rotate, scale, hide or highlight your first-person hands.");
		whileEnabled(this::listenForHighlights, this::listenForHighlights);
	}

	/** Your hands and what you hold, as the camera sees them with these settings. */
	@Override
	public Widget preview() {
		return Preview.viewModel().height(140);
	}

	private void listenForHighlights() {
		boolean listen = isEnabled() && (handHighlight.get() || itemHighlight.get());
		if (listen == listening) return;
		listening = listen;
		if (listen) Myriad.events().subscribe(highlights);
		else Myriad.events().unsubscribe(highlights);
	}

	private final class HandHighlights {
		@Subscribe
		private void onHighlight(HighlightEvent.Hand e) {
			if (e.part() == HighlightEvent.Hand.Part.ARM) {
				if (handHighlight.get()) e.highlight(handStyle.style(), handColor.argb());
			} else if (itemHighlight.get()) {
				e.highlight(itemStyle.style(), itemColor.argb());
			}
		}
	}

	/** How many ticks a swing lasts with Swing Speed applied, from vanilla's {@code ticks}. */
	public static int swingDuration(int ticks) {
		ViewModel vm = Modules.active(ViewModel.class);
		if (vm == null || vm.swingSpeed.get() == 1) return ticks;
		return Math.max(1, (int) Math.round(ticks / vm.swingSpeed.get()));
	}

	/** Whether the hand should skip the lower-and-raise animation when the held item changes. */
	public static boolean noSwitchAnimation() {
		ViewModel vm = Modules.active(ViewModel.class);
		return vm != null && vm.noSwitch.get();
	}

}
