package dev.myriadclient.essentials.modules.player;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.BlockBreakEvent;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;

/**
 * Hit a block once and it's mined for you with packets, through the shared breaking service, while your hand stays
 * free: the best tool is swapped in only to start and finish (brought into the hotbar from your inventory when you
 * stand still). Any block your crosshair reaches can be picked, measured as the server does, to the block's nearest
 * point; one you walk out of reach of is dropped. A mined block stays on screen until the server says it's gone, so a
 * finish it refuses never leaves a ghost block. Hold the button and drag to line up every block you pass over; your
 * hand swings while you hold, and not otherwise (the swing Grim expects with each break packet is sent unseen).
 * Timed for Grim (2b2t):
 * vanilla's full break time from the start, judged by the tool you start with, and vanilla's pause between blocks, so
 * finishes aren't refused. The block you mined last can be re-broken with a single packet when something is placed
 * back there, once enough time has passed for that block.
 * <p>
 * Fast is the decoy trick for older Grim builds (see {@code Breaking.Mode.FAST_GRIM}; current Grim catches it): blocks
 * finish at 70% of the time, with decoy start packets that kept older Grim from noticing, and Double Break mines a
 * second block meanwhile.
 */
public class PacketMine extends Module {
	private final BoolSetting fast = sgGeneral.bool("Fast").description("Always finish at 70% of the time, as 2b2t clients do: once Grim's allowance "
		+ "for early finishes runs out, a decoy start above the build limit hides the speed (needs ViaVersion, as 2b2t has). Each decoy is an AirLiquidBreak flag.").build();
	private final BoolSetting doubleBreak = sgGeneral.bool("Double Break").description("Mine a second block while the server finishes the first.")
		.defaultValue(true).visible(fast::get).build();
	private final BoolSetting queue = sgGeneral.bool("Queue").description("A new click adds to the line, instead of replacing the blocks still waiting. "
		+ "Dragging while you hold always adds.").build();
	private final BoolSetting autoRebreak = sgGeneral.bool("Auto Rebreak").description("Break the last mined block again as soon as something placed there can be.").build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Face the block when starting and finishing, for servers that check.").build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();

	/** Queued blocks: faint and dotted, so each one in a line shows (touching ones share an outline) without competing with the block being mined. */
	private static final HighlightStyle QUEUED = HighlightStyle.OUTLINE.withOutlineWidth(1).withFill(HighlightStyle.Fill.DOTS).withFillOpacity(0.6f);
	/** The block being mined: no outline, just a fill that thickens as it breaks (see {@link #onHighlight}). */
	private static final HighlightStyle MINING = HighlightStyle.OUTLINE.withOutlineWidth(0).withFill(HighlightStyle.Fill.SOLID);

	/** Positions this module asked to break that haven't finished. */
	private final List<BlockPos> mining = new ArrayList<>();
	private BlockPos last;
	/** The player's tick a swing last went to the server in. */
	private int swungTick = -1;

	public PacketMine() {
		super(Categories.PLAYER, "Packet Mine", "Mines the blocks you hit for you, with your hand free.");
	}

	@Override
	protected void onDisable() {
		mining.clear();
		last = null;
	}

	@Override
	public String hudInfo() {
		return mining.isEmpty() ? null : String.valueOf(mining.size());
	}

	/** A click: a new selection (replacing what's waiting, unless Queue is on). Vanilla swings for the click. */
	@Subscribe
	private void onStart(BlockBreakEvent.Start e) {
		if (!inGame() || mc.player.isCreative()) return;
		e.cancel();
		select(e.pos(), !queue.get());
	}

	/**
	 * Holding the button: vanilla would keep mining the block under the crosshair; instead every block it passes over
	 * joins the line, and the hand swings as long as you hold.
	 */
	@Subscribe
	private void onProgress(BlockBreakEvent.Progress e) {
		if (!inGame() || mc.player.isCreative()) return;
		e.cancel();
		select(e.pos(), false);
		// The hand swings on screen every tick you hold; the server only needs one swing a tick, and vanilla's for the
		// click (or the breaking service's with a start) may have gone already.
		if (swungTick == mc.player.tickCount) mc.player.swing(InteractionHand.MAIN_HAND, false);
		else mc.player.swing(InteractionHand.MAIN_HAND);
	}

	@Subscribe(packets = ServerboundSwingPacket.class)
	private void onSend(PacketEvent.Send e) {
		if (mc.player != null) swungTick = mc.player.tickCount;
	}

	private void select(BlockPos pos, boolean replaceWaiting) {
		if (mining.contains(pos)) return;
		if (replaceWaiting) {
			// Drop what's still waiting its turn; blocks already being mined carry on.
			for (BlockPos p : new ArrayList<>(mining)) {
				if (Myriad.breaking().progress(p) == 0) {
					Myriad.breaking().cancel(p);
					mining.remove(p);
				}
			}
		}
		Breaking.Attempt a = Myriad.breaking().breakBlock(this, pos, 0, options());
		if (!a.accepted()) return;
		BlockPos key = a.pos();
		mining.add(key);
		a.result().thenAccept(broken -> {
			mining.remove(key);
			if (broken) last = key;
		});
	}

	private Breaking.Options options() {
		Breaking.Mode mode = fast.get() ? Breaking.Mode.FAST_GRIM : Breaking.Mode.PACKET;
		// No swings of its own: your hand only moves while you hold the button.
		// The block stays on screen until the server says it's gone: a refused finish never shows a ghost block.
		return Breaking.Options.PACKET.withMode(mode).withRotate(rotate.get()).withSwing(false).withRange(Reach.blockRange()).withDoubleBreak(fast.get() && doubleBreak.get())
			.withWaitForServer(true);
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Post e) {
		mining.removeIf(p -> !Myriad.breaking().isPending(p));
		if (autoRebreak.get() && last != null && Myriad.breaking().canRebreak(last)) select(last, false);
	}

	/**
	 * Shader outlines, drawn over the blocks rather than on their faces (a box there loses to the block's own faces):
	 * the queue faint and dotted (touching blocks share an outline), the block being mined a fill that thickens as it
	 * breaks and staying filled until the server says it's gone, and the block Auto Rebreak watches, outlined.
	 */
	@Subscribe(inGame = true)
	private void onHighlight(HighlightEvent.Shapes e) {
		int c = color.argb();
		for (BlockPos pos : mining) {
			float progress = Myriad.breaking().progress(pos);
			if (progress == 0) e.block(pos, QUEUED, ColorUtil.withAlpha(c, 110));
			else e.block(pos, MINING.withFillOpacity(0.15f + 0.5f * progress), c, 0.002);
		}
		if (autoRebreak.get() && last != null && !mining.contains(last)) e.block(last, HighlightStyle.OUTLINE, ColorUtil.withAlpha(c, 140));
	}
}
