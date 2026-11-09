package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.GameReadyEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.essentials.modules.render.tab.TabRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * The player list (Tab), drawn in Myriad's own look in place of Minecraft's: more players than its 80, as many rows a
 * column as you like, what each row shows up to you, friends, enemies and you picked out and optionally listed first.
 * Drawn by TabRenderer, in place of Minecraft's list by PlayerTabOverlayMixin.
 */
public class ExtraTab extends Module {
	/** Ping text colours by latency, as Minecraft's bars step: under 150, 300, 600, 1000 ms, and over. */
	private static final int[] PING_COLORS = {0xFF55FF55, 0xFFFFFF55, 0xFFFFAA00, 0xFFFF5555, 0xFFAA0000};
	private static final int[] PING_STEPS = {150, 300, 600, 1000};
	private static final Identifier MYRIAD_HUD = Identifier.fromNamespaceAndPath("myriad", "hud");

	public enum Ping {
		OFF, BARS, NUMBER
	}

	public enum Sort {
		SERVER, FRIENDS_FIRST, NAME, PING
	}

	private final SettingGroup sgList = settings.group("List");
	public final IntSetting players = sgList.integer("Players").description("The most players listed (Minecraft lists 80).")
		.defaultValue(200).range(20, 1000).build();
	public final IntSetting rows = sgList.integer("Rows").description("Players a column, before another column starts (Minecraft's: 20). More are fitted in when the columns run out of room.")
		.defaultValue(20).range(10, 60).build();
	public final EnumSetting<Sort> sort = sgList.oneOf("Sort", Sort.SERVER).description("Server: the order Minecraft lists them in. Friends First: you, friends and enemies at the top.").build();
	public final BoolSetting heads = sgList.bool("Heads").description("Each player's face (where the server has real skins).").defaultValue(true).build();
	public final EnumSetting<Ping> ping = sgList.oneOf("Ping", Ping.NUMBER).description("Off, bars, or in milliseconds, coloured as the bars step.").build();
	public final BoolSetting scoreboard = sgList.bool("Scoreboard").description("The score a server shows in the list.").defaultValue(true).build();
	public final BoolSetting headerFooter = sgList.bool("Header And Footer").description("The server's lines above and below the list.")
		.defaultValue(true).build();

	private final SettingGroup sgHighlight = settings.group("Highlight");
	public final BoolSetting friends = sgHighlight.bool("Friends").defaultValue(true).build();
	public final ColorSetting friendColor = sgHighlight.color("Friend Color").defaultValue(SettingColor.role(SettingColor.Mode.CYAN)).visible(friends::get).build();
	public final BoolSetting enemies = sgHighlight.bool("Enemies").description("Players added with .enemy.").defaultValue(true).build();
	public final ColorSetting enemyColor = sgHighlight.color("Enemy Color").defaultValue(SettingColor.role(SettingColor.Mode.RED)).visible(enemies::get).build();
	public final BoolSetting you = sgHighlight.bool("You").defaultValue(true).build();
	public final ColorSetting youColor = sgHighlight.color("Your Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(you::get).build();

	private final TabRenderer renderer = new TabRenderer(this);
	private boolean layerAdded;

	public ExtraTab() {
		super(Tags.RENDER, "Extra Tab", "The player list in Myriad's look: more players and rows, choose what's shown, friends, enemies and you picked out.");
		Myriad.events().subscribe(new Layer());
	}

	@Override
	protected void onDisable() {
		renderer.close();
	}

	/** The module while it's on, else null. */
	public static @Nullable ExtraTab on() {
		ExtraTab m = Modules.get(ExtraTab.class);
		return m != null && m.isEnabled() ? m : null;
	}

	public void show(int screenWidth, Scoreboard board, @Nullable Objective objective, @Nullable Component header, @Nullable Component footer) {
		renderer.show(screenWidth, board, objective, header, footer);
	}

	/** The list's own layer, after Myriad's HUD so that it covers it; added once the game is up, when that layer exists. */
	private final class Layer {
		@Subscribe
		private void onReady(GameReadyEvent e) {
			if (layerAdded) return;
			layerAdded = true;
			HudElementRegistry.attachElementAfter(MYRIAD_HUD, Identifier.fromNamespaceAndPath("myriad-essentials", "extra_tab"),
				(graphics, tick) -> renderer.drawPending(graphics));
		}
	}

	/** The ping as text, {@code ?} while it's not known. */
	public static String pingText(int latency) {
		return latency < 0 ? "?" : latency + "ms";
	}

	/** The colour for {@code latency}, by the steps Minecraft's bars take; grey while it's not known. */
	public static int pingColor(int latency) {
		if (latency < 0) return 0xFFAAAAAA;
		for (int i = 0; i < PING_STEPS.length; i++) if (latency < PING_STEPS[i]) return PING_COLORS[i];
		return PING_COLORS[PING_COLORS.length - 1];
	}
}
