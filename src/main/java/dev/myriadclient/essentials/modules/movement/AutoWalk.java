package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;

/**
 * Holds forward for you, screens open or not (chat, your inventory), so you can type or sort while you walk. Holding
 * back stops you; sprint, jump and the sides still work as usual.
 */
public class AutoWalk extends Module {
	public AutoWalk() {
		super(Categories.MOVEMENT, "Auto Walk", "Walk forward without holding the key.");
	}

	@Subscribe
	private void onInput(InputEvent e) {
		e.forward = true;
	}
}
