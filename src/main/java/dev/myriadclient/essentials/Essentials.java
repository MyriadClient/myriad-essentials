package dev.myriadclient.essentials;

import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.MyriadAddon;
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.util.Baritone;
import dev.myriadclient.essentials.hud.ArmorPanel;
import dev.myriadclient.essentials.hud.BindsPanel;
import dev.myriadclient.essentials.hud.ChestCountPanel;
import dev.myriadclient.essentials.hud.CoordinatesPanel;
import dev.myriadclient.essentials.hud.DirectionPanel;
import dev.myriadclient.essentials.hud.EffectsPanel;
import dev.myriadclient.essentials.hud.FpsPanel;
import dev.myriadclient.essentials.hud.HealthPanel;
import dev.myriadclient.essentials.hud.LookingAtPanel;
import dev.myriadclient.essentials.hud.ModuleListPanel;
import dev.myriadclient.essentials.hud.PingPanel;
import dev.myriadclient.essentials.hud.PlayerCountPanel;
import dev.myriadclient.essentials.hud.SpeedPanel;
import dev.myriadclient.essentials.hud.TotemsPanel;
import dev.myriadclient.essentials.hud.TpsPanel;
import dev.myriadclient.essentials.hud.WatermarkPanel;
import dev.myriadclient.essentials.modules.combat.AutoArmor;
import dev.myriadclient.essentials.modules.combat.AutoDisconnect;
import dev.myriadclient.essentials.modules.combat.KillAura;
import dev.myriadclient.essentials.modules.combat.Offhand;
import dev.myriadclient.essentials.modules.movement.AutoWalk;
import dev.myriadclient.essentials.modules.movement.ElytraFly;
import dev.myriadclient.essentials.modules.movement.ElytraSwap;
import dev.myriadclient.essentials.modules.movement.InventoryMove;
import dev.myriadclient.essentials.modules.movement.LockRotation;
import dev.myriadclient.essentials.modules.movement.RocketBoost;
import dev.myriadclient.essentials.modules.movement.Velocity;
import dev.myriadclient.essentials.modules.player.AutoEat;
import dev.myriadclient.essentials.modules.player.AutoTool;
import dev.myriadclient.essentials.modules.player.InventoryTweaks;
import dev.myriadclient.essentials.modules.player.MiddleClick;
import dev.myriadclient.essentials.modules.player.PacketMine;
import dev.myriadclient.essentials.modules.player.Reach;
import dev.myriadclient.essentials.modules.player.StackReplenish;
import dev.myriadclient.essentials.modules.player.WallInteract;
import dev.myriadclient.essentials.modules.render.BaritoneRender;
import dev.myriadclient.essentials.modules.render.BlockESP;
import dev.myriadclient.essentials.modules.render.BlockHighlight;
import dev.myriadclient.essentials.modules.render.ESP;
import dev.myriadclient.essentials.modules.render.FreeLook;
import dev.myriadclient.essentials.modules.render.Freecam;
import dev.myriadclient.essentials.modules.render.FullBright;
import dev.myriadclient.essentials.modules.render.Nametags;
import dev.myriadclient.essentials.modules.render.NoRender;
import dev.myriadclient.essentials.modules.render.Storage;
import dev.myriadclient.essentials.modules.render.TimeOfDay;
import dev.myriadclient.essentials.modules.render.Tooltips;
import dev.myriadclient.essentials.modules.render.Tracers;
import dev.myriadclient.essentials.modules.render.ViewModel;
import dev.myriadclient.essentials.modules.render.Zoom;
import dev.myriadclient.essentials.modules.world.AirPlace;
import dev.myriadclient.essentials.modules.world.BaritoneSettings;
import dev.myriadclient.essentials.modules.world.Scaffold;

import java.util.List;

import static dev.myriadclient.api.ui.PanelType.Anchor.BOTTOM_LEFT;
import static dev.myriadclient.api.ui.PanelType.Anchor.TOP;
import static dev.myriadclient.api.ui.PanelType.Anchor.TOP_LEFT;
import static dev.myriadclient.api.ui.PanelType.Anchor.TOP_RIGHT;

/**
 * The stock module set. It is an ordinary Myriad addon: it uses nothing but the public API, so removing this jar
 * leaves Myriad fully working with no modules.
 */
public final class Essentials implements MyriadAddon {
	@Override
	public void initialize(AddonContext ctx) {
		// Modules the ready-made control panels below combine.
		ElytraSwap elytraSwap = new ElytraSwap();
		LockRotation lockRotation = new LockRotation();
		ElytraFly elytraFly = new ElytraFly();
		AutoEat autoEat = new AutoEat();

		// The menu's windows, by what the modules are for.
		ModuleGroup combat = ctx.registerGroup("Combat", Icons.CROSSHAIRS);
		ModuleGroup travel = ctx.registerGroup("Travel", Icons.PLANE);
		ModuleGroup building = ctx.registerGroup("Building & Mining", Icons.CUBES);
		ModuleGroup inventory = ctx.registerGroup("Inventory", Icons.ARCHIVE);
		ModuleGroup visuals = ctx.registerGroup("Visuals", Icons.EYE);

		// Building blocks other modules use (and control panels combine with them). First: they load before the modules
		// whose former settings they take over (Elytra Fly, Auto Armor).
		ctx.registerModules(travel, elytraSwap, lockRotation);
		ctx.registerModules(combat, new KillAura(), new Offhand(), new AutoArmor(), autoEat, new Velocity(), new AutoDisconnect());
		ctx.registerModules(travel, elytraFly, new RocketBoost(), new AutoWalk(), new InventoryMove());
		ctx.registerModules(building, new Scaffold(), new AirPlace(), new PacketMine(), new AutoTool(), new Reach(), new WallInteract());
		ctx.registerModules(inventory, new InventoryTweaks(), new StackReplenish(), new MiddleClick());
		ctx.registerModules(visuals, new ESP(), new Tracers(), new Nametags(), new Storage(), new BlockESP(), new BlockHighlight(), new FullBright(),
			new NoRender(), new TimeOfDay(), new ViewModel(), new Zoom(), new FreeLook(), new Freecam(), new Tooltips());
		// Baritone's settings, made from Baritone's own list, and its path and goal drawn: only when it's installed.
		List<Baritone.SettingInfo> baritone = Baritone.settings();
		if (!baritone.isEmpty()) {
			ctx.registerModule(building, new BaritoneSettings(baritone));
			ctx.registerModule(visuals, new BaritoneRender());
		}

		// Ready-made control panels: one bind turns the set on, Elytra Fly flying the type given while it's on.
		ctx.registerControlPanel(ControlPreset.builder("Highway")
			.module(elytraFly, m -> m.set(elytraFly.type, ElytraFly.RECAST))
			.module(elytraSwap)
			.module(autoEat)
			.build());
		ctx.registerControlPanel(ControlPreset.builder("Cruise")
			.module(elytraFly, m -> m.set(elytraFly.type, ElytraFly.ALTITUDE))
			.module(elytraSwap)
			.module(lockRotation)
			.module(autoEat)
			.build());

		// HUD elements. The ones with a position are placed on a fresh install; the rest are added from the HUD workspace.
		ctx.registerHud("Watermark", "", WatermarkPanel::new, TOP_LEFT, 0, 0);
		ctx.registerHud("Module List", "", ModuleListPanel::new, TOP_RIGHT, 0, 0);
		ctx.registerHud("Looking At", "", LookingAtPanel::new, TOP, 0, 6);
		ctx.registerHud("Coordinates", "", CoordinatesPanel::new, BOTTOM_LEFT, 0, 0);
		ctx.registerHud("Armor", "", ArmorPanel::new);
		ctx.registerHud("Binds", "", BindsPanel::new);
		ctx.registerHud("Chest Count", "", ChestCountPanel::new);
		ctx.registerHud("Direction", "", DirectionPanel::new);
		ctx.registerHud("Effects", "", EffectsPanel::new);
		ctx.registerHud("FPS", "", FpsPanel::new);
		ctx.registerHud("HP", "", HealthPanel::new);
		ctx.registerHud("Ping", "", PingPanel::new);
		ctx.registerHud("Player Count", "", PlayerCountPanel::new);
		ctx.registerHud("Speed", "", SpeedPanel::new);
		ctx.registerHud("Totems", "", TotemsPanel::new);
		ctx.registerHud("TPS", "", TpsPanel::new);
	}
}
