package dev.myriadclient.essentials.util;

import dev.myriadclient.api.service.AntiCheat;

/**
 * Whether a module turns to look at what it places: Auto follows the server (on where the anti-cheat checks the look of
 * each click, current Grim; off on 2b2t and vanilla servers, where it's only slower), or always on or off.
 */
public enum RotateMode {
	AUTO, ON, OFF;

	/** Whether to turn now. */
	public boolean turns() {
		return switch (this) {
			case AUTO -> AntiCheat.inEffect().checksLookOnActions();
			case ON -> true;
			case OFF -> false;
		};
	}
}
