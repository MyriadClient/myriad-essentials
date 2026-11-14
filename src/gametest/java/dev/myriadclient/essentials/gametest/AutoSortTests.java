package dev.myriadclient.essentials.gametest;

import dev.myriadclient.essentials.gametest.SortScenes.ShulkerSort;
import dev.myriadclient.essentials.gametest.SortScenes.Sort;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;

/**
 * Auto Sort: a double chest of scattered part stacks sorted as you open it (merged, packed from the first slot, nothing
 * lost, the cursor empty), with your inventory full, stopped by your own click, opened by hand, and with shulker boxes in
 * the mix. The chest stands two blocks east of you (see {@link SortScenes}).
 */
public final class AutoSortTests extends EssentialsSuite {
	@Test(tags = "inventory")
	public void doubleChest(TestContext t) {
		SortScenes.sortPass(t, Sort.AUTO);
	}

	@Test(tags = "inventory")
	public void fullInventory(TestContext t) {
		SortScenes.sortPass(t, Sort.AUTO.withFullInventory());
	}

	@Test(tags = "inventory")
	public void yourClick(TestContext t) {
		SortScenes.sortPass(t, Sort.AUTO.clickingAfter(12));
	}

	@Test(tags = "inventory")
	public void openedByHand(TestContext t) {
		SortScenes.sortPass(t, Sort.AUTO.openedByHand());
	}

	@Test(tags = "inventory", timeoutTicks = 2000)
	public void shulkers(TestContext t) {
		SortScenes.shulkerSortPass(t, ShulkerSort.AUTO);
	}

	@Test(tags = "inventory", timeoutTicks = 2000)
	public void shulkersInYourInventory(TestContext t) {
		SortScenes.shulkerSortPass(t, ShulkerSort.AUTO_CARRY);
	}
}
