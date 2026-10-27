package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The player list (Tab) with more in it: more players than Minecraft's 80, as many rows a column as you like, each
 * player's ping in milliseconds instead of bars, friends and you picked out, the server's header and footer left out
 * if you like. Read by PlayerTabOverlayMixin.
 */
public class ExtraTab extends Module {
	/** Ping text colours by latency, as Minecraft's bars step: under 150, 300, 600, 1000 ms, and over. */
	private static final int[] PING_COLORS = {0xFF55FF55, 0xFFFFFF55, 0xFFFFAA00, 0xFFFF5555, 0xFFAA0000};
	private static final int[] PING_STEPS = {150, 300, 600, 1000};

	public enum Ping {
		BARS, NUMBER
	}

	private final SettingGroup sgList = settings.group("List");
	public final IntSetting players = sgList.integer("Players").description("The most players listed (Minecraft lists 80).")
		.defaultValue(200).range(20, 1000).build();
	public final IntSetting rows = sgList.integer("Rows").description("Players a column, before another column starts (Minecraft's: 20).")
		.defaultValue(20).range(10, 60).build();
	public final EnumSetting<Ping> ping = sgList.oneOf("Ping", Ping.NUMBER).description("Bars: Minecraft's. Number: in milliseconds, coloured as the bars step.").build();
	public final BoolSetting headerFooter = sgList.bool("Header And Footer").description("The server's lines above and below the list.")
		.defaultValue(true).build();

	private final SettingGroup sgHighlight = settings.group("Highlight");
	public final BoolSetting friends = sgHighlight.bool("Friends").defaultValue(true).build();
	public final ColorSetting friendColor = sgHighlight.color("Friend Color").defaultValue(SettingColor.role(SettingColor.Mode.CYAN)).visible(friends::get).build();
	public final BoolSetting you = sgHighlight.bool("You").defaultValue(true).build();
	public final ColorSetting youColor = sgHighlight.color("Your Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(you::get).build();

	public ExtraTab() {
		super(Tags.RENDER, "Extra Tab", "The player list with more players and rows, ping in milliseconds, friends and you picked out.");
	}

	/** The module while it's on, else null. */
	public static @Nullable ExtraTab on() {
		ExtraTab m = Modules.get(ExtraTab.class);
		return m != null && m.isEnabled() ? m : null;
	}

	/** {@code name} as the list shows it: in your colour or a friend's, plain, else as it was. */
	public Component name(PlayerInfo info, Component name) {
		int color;
		if (you.get() && mc.player != null && info.getProfile().id().equals(mc.player.getUUID())) color = youColor.argb();
		else if (friends.get() && Myriad.friends().isFriend(info.getProfile().name())) color = friendColor.argb();
		else return name;
		return Component.literal(name.getString()).withColor(color & 0xFFFFFF);
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
