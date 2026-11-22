package dev.myriadclient.essentials.modules.automation;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plants the crop you hold on every empty farmland in reach, farthest first: seeds, carrots, potatoes, beetroot, melon
 * and pumpkin seeds, torchflower seeds and pitcher pods (and nether wart on soul sand). The placements go through core,
 * so they follow the server's rules (the look turned for them where it's checked, and the place rate kept).
 */
public class AutoFarmer extends Module {
	public AutoFarmer() {
		super(Tags.AUTOMATION, "Auto Farmer", "Plants the crop you hold on empty farmland in reach.");
	}

	/** The crop block {@code stack} plants, if it's one that grows on farmland (or nether wart); else null. */
	private static @Nullable Block crop(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return null;
		Block block = item.getBlock();
		// Wheat, carrots, potatoes, beetroot and torchflowers are crops; melons and pumpkins stems; nether wart the soul sand one.
		return block instanceof CropBlock || block instanceof StemBlock || block instanceof PitcherCropBlock || block instanceof NetherWartBlock ? block : null;
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Pre e) {
		int slot = mc.player.getInventory().getSelectedSlot();
		Block crop = crop(mc.player.getInventory().getItem(slot));
		if (crop == null || mc.gui.screen() != null) return;
		Placement.Options options = Placement.Options.forServer();
		int r = (int) Math.ceil(options.range()) + 1;
		BlockPos feet = mc.player.blockPosition();
		Vec3 eyes = mc.player.getEyePosition();
		List<BlockPos> spots = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-r, -r - 1, -r), feet.offset(r, r, r))) {
			if (!mc.level.getBlockState(p).isAir()) continue;
			Block below = mc.level.getBlockState(p.below()).getBlock();
			boolean soil = crop instanceof NetherWartBlock ? below == Blocks.SOUL_SAND : below instanceof FarmlandBlock;
			if (soil && !Myriad.placement().isPending(p)) spots.add(p.immutable());
		}
		// Farthest first: a look at a spot never passes over a crop just planted nearer (one in the way of the look fails
		// the server's check of what you clicked).
		spots.sort(Comparator.comparingDouble(p -> -Vec3.atCenterOf(p).distanceToSqr(eyes)));
		for (BlockPos p : spots) {
			Placement.Attempt a = Myriad.placement().place(this, p, slot, options);
			// Out of budget for this tick: the rest next tick.
			if (a.check() == Placement.Check.RATE_LIMITED) break;
		}
	}
}
