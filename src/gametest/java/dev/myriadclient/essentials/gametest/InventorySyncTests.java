package dev.myriadclient.essentials.gametest;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Core's inventory while something is borrowed: the hotbar shows what was there, and your own clicks in the inventory
 * screen act on what it shows (the item you see, wherever it really is), not on the borrowed one.
 */
public final class InventorySyncTests extends EssentialsSuite {
	private static String describe(ItemStack s) {
		return s.isEmpty() ? "nothing" : s.getCount() + " " + BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	@Test(name = "click_what_you_see", tags = "inventory", timeoutTicks = 600)
	public void clickWhatYouSee(TestContext t) {
		// A full hotbar but for the sword in slot 3, so the borrow can only go there; you hold slot 0.
		t.player().give(0, "stone", 1).give(1, "dirt", 1).give(2, "dirt", 2).give(3, "diamond_sword").give(4, "dirt", 4).give(5, "dirt", 5)
			.give(6, "dirt", 6).give(7, "dirt", 7).give(8, "dirt", 8).give(20, "firework_rocket", 16).select(0);
		t.waitTicks(10);
		Object owner = new Object();
		int into = -1;
		for (int i = 0; i < 40 && into < 0; i++) {
			into = t.onGame(() -> Myriad.inventory().borrow(owner, 20, s -> s.is(Items.DIAMOND_SWORD)));
			if (into < 0) t.waitTicks(1);
		}
		int slot = into;
		// Held, so it stays borrowed while the screen is open.
		t.onGame(() -> Myriad.inventory().hold(owner, slot, 600));
		t.waitTicks(10);
		String shown = t.onGame(() -> describe(Myriad.inventory().shown(3)));
		String real = t.onGame(() -> describe(Minecraft.getInstance().player.getInventory().getItem(3)));
		// Your inventory open, a click on hotbar slot 3 (menu slot 39), where the sword shows: the sword is picked up.
		SortScenes.script(t, "key e", "wait 10", "cursor slot 39", "mouse 0 click", "wait 10");
		String carried = SortScenes.carried(t);
		// Put down where it came from (the same slot as you see it), the screen closed, the borrow ended.
		SortScenes.script(t, "mouse 0 click", "wait 10", "key escape", "wait 5");
		t.onGame(() -> {
			Myriad.inventory().release(owner);
			Myriad.inventory().giveBack(owner);
		});
		t.waitTicks(40);
		String me = "entity " + t.player().name();
		boolean back = t.server().passes("if items " + me + " container.3 diamond_sword") && t.server().passes("if items " + me + " container.20 firework_rocket");
		String detail = String.format(Locale.ROOT, "borrowed into %d; slot 3 showed %s (held %s); clicked: %s on the cursor; afterwards %s", slot, shown, real, carried,
			back ? "sword and rockets home" : "not as they were");
		t.check(slot == 3 && shown.equals("1 diamond_sword") && real.equals("16 firework_rocket") && carried.equals("1 diamond_sword") && back, detail);
		t.note(detail);
	}
}
