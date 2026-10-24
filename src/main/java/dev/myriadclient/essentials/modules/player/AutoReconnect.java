package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.DisconnectEvent;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.essentials.modules.combat.AutoDisconnect;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;

/**
 * Joins the server you were on again when you're kicked or the connection drops, after a few seconds, and keeps
 * trying while it can't get in (a server restarting, a full queue). The disconnect screen shows when, on a button that
 * joins at once; going back to the server list stops it. Never after Auto Disconnect made you leave, nor in singleplayer.
 */
public class AutoReconnect extends Module {
	/** A disconnect this soon after Auto Disconnect left is its own. */
	private static final long OWN_LEAVE_MS = 3000;

	private final IntSetting delay = sgGeneral.integer("Delay").description("Seconds on the disconnect screen before joining again.")
		.defaultValue(5).range(1, 60).build();

	/** When to join again ({@code System.currentTimeMillis}), or -1. */
	private long joinAt = -1;
	/** Auto Disconnect left on purpose: no going back until you join a server yourself. */
	private boolean held;

	public AutoReconnect() {
		super(Tags.AUTOMATION, "Auto Reconnect", "Join the server you were on again after you're kicked or lose connection.");
	}

	@Override
	protected void onDisable() {
		joinAt = -1;
	}

	@Subscribe
	private void onDisconnect(DisconnectEvent e) {
		AutoDisconnect leaver = Modules.get(AutoDisconnect.class);
		held = leaver != null && System.currentTimeMillis() - leaver.leftAt() < OWN_LEAVE_MS;
	}

	/** Every disconnect screen while there's a server to go back to: after a kick, and after a try that didn't get in. */
	@Subscribe
	private void onScreen(ScreenOpenEvent e) {
		if (e.screen() instanceof DisconnectedScreen) {
			joinAt = held || Myriad.server().lastAddress() == null ? -1 : System.currentTimeMillis() + delay.get() * 1000L;
		} else if (e.screen() != null && !(e.screen() instanceof ConnectScreen)) {
			// Somewhere else (the server list, the title screen): you've stopped it. Joining's own screen doesn't.
			joinAt = -1;
		}
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (joinAt < 0 || System.currentTimeMillis() < joinAt) return;
		joinAt = -1;
		if (mc.gui.screen() instanceof DisconnectedScreen) joinNow();
	}

	/** Joins at once (the disconnect screen's button). */
	public void joinNow() {
		joinAt = -1;
		held = false;
		Myriad.server().reconnect();
	}

	/** The disconnect screen's button: when it joins again, or just "Reconnect" while this is off or held. */
	public String buttonText() {
		if (!isEnabled() || joinAt < 0) return "Reconnect";
		long seconds = Math.max(0, (joinAt - System.currentTimeMillis() + 999) / 1000);
		return "Reconnect in " + seconds + " s";
	}
}
