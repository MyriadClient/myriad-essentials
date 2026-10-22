package dev.myriadclient.essentials.modules.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.BlockBreakEvent;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.HighlightSettings;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.BreakingSettings;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
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
 * Mode Fast is the decoy trick for older Grim builds (see {@code Breaking.Mode.FAST_GRIM}; current Grim catches it):
 * blocks finish at 70% of the time, with decoy start packets that kept older Grim from noticing, and Double Break mines
 * a second block meanwhile.
 */
public class PacketMine extends Module {
	private final BreakingSettings breaking = new BreakingSettings(sgGeneral);
	private final BoolSetting queue = sgGeneral.bool("Queue").description("A new click adds to the line, instead of replacing the blocks still waiting. "
		+ "Dragging while you hold always adds.").build();
	private final BoolSetting autoRebreak = sgGeneral.bool("Auto Rebreak").description("Break the last mined block again as soon as something placed there can be.").build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Face the block when starting and finishing, for servers that check.").build();
	private final ColorSetting color = sgGeneral.color("Color").description("Queued blocks and the block Auto Rebreak watches.")
		.defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();

	/** Opacities the block being mined fades between by default: faint as it starts, filled in as it breaks. */
	private static final int START_ALPHA = 38, END_ALPHA = 166;
	private final SettingGroup sgMining = settings.group("Mining");
	private final ColorSetting startColor = sgMining.color("Start Color").description("The block being mined as it starts breaking; its opacity fades the whole look (fill, outline and glow).")
		.defaultValue(SettingColor.role(SettingColor.Mode.ACCENT, START_ALPHA)).build();
	private final ColorSetting endColor = sgMining.color("End Color").description("The block being mined once it's broken, until the server says it's gone; "
		+ "the colour and opacity blend from Start as it breaks.").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT, END_ALPHA)).build();
	/** The block being mined: by default no outline, just a fill that thickens as it breaks (see {@link #onHighlight}). */
	private final HighlightSettings miningLook = HighlightSettings.builder(sgMining)
		.defaults(HighlightStyle.OUTLINE.withOutlineWidth(0).withFill(HighlightStyle.Fill.SOLID).withFillOpacity(1)).build();

	/** Queued blocks: faint and dotted, so each one in a line shows (touching ones share an outline) without competing with the block being mined. */
	private static final HighlightStyle QUEUED = HighlightStyle.OUTLINE.withOutlineWidth(1).withFill(HighlightStyle.Fill.DOTS).withFillOpacity(0.6f);

	/** Positions this module asked to break that haven't finished. */
	private final List<BlockPos> mining = new ArrayList<>();
	private BlockPos last;
	/** The player's tick a swing last went to the server in. */
	private int swungTick = -1;

	public PacketMine() {
		super(Tags.PLAYER, "Packet Mine", "Mines the blocks you hit for you, with your hand free.");
	}

	/** 2: the Fast switch became the standard breaking Mode. 3: the block being mined got its own Start and End colours. */
	@Override
	public int settingsVersion() {
		return 3;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			saved.move("General", "Fast", "General", "Mode");
			saved.map("General", "Mode", v -> v.getAsBoolean() ? new JsonPrimitive("FAST") : null);
		}
		if (fromVersion == 2) {
			// The mined block was Color, faded from 15% to 65% of its opacity: a chosen Color carries over to both ends.
			saved.get("General", "Color").ifPresent(c -> {
				saved.set("Mining", "Start Color", faded(c, START_ALPHA));
				saved.set("Mining", "End Color", faded(c, END_ALPHA));
			});
		}
	}

	/** A saved colour ({@code {"color": "#AARRGGBB", "mode": ...}}, or just the hex) with its opacity scaled by {@code alpha}/255. */
	private static JsonElement faded(JsonElement saved, int alpha) {
		JsonObject o = saved.isJsonObject() ? saved.getAsJsonObject().deepCopy() : new JsonObject();
		if (!saved.isJsonObject()) {
			o.addProperty("color", saved.getAsString());
			o.addProperty("mode", SettingColor.Mode.STATIC.name());
		}
		int c = ColorUtil.parseHex(o.get("color").getAsString());
		o.addProperty("color", ColorUtil.toHex(ColorUtil.withAlpha(c, Math.round(ColorUtil.alpha(c) * alpha / 255f))));
		return o;
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
		// No swings of its own: your hand only moves while you hold the button.
		// The block stays on screen until the server says it's gone: a refused finish never shows a ghost block.
		return breaking.options().withRotate(rotate.get()).withSwing(false).withRange(Reach.blockRange()).withWaitForServer(true);
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Post e) {
		mining.removeIf(p -> !Myriad.breaking().isPending(p));
		if (autoRebreak.get() && last != null && Myriad.breaking().canRebreak(last)) select(last, false);
	}

	/**
	 * Shader outlines, drawn over the blocks rather than on their faces (a box there loses to the block's own faces):
	 * the queue faint and dotted (touching blocks share an outline), the block being mined in Mining's look, blending
	 * from Start to End Color as it breaks and staying at End until the server says it's gone, and the block Auto
	 * Rebreak watches, outlined.
	 */
	@Subscribe(inGame = true)
	private void onHighlight(HighlightEvent.Shapes e) {
		int c = color.argb();
		for (BlockPos pos : mining) {
			float progress = Myriad.breaking().progress(pos);
			if (progress == 0) e.block(pos, QUEUED, ColorUtil.withAlpha(c, 110));
			else e.block(pos, miningLook.style(), ColorUtil.lerp(startColor.argb(), endColor.argb(), progress), 0.002);
		}
		if (autoRebreak.get() && last != null && !mining.contains(last)) e.block(last, HighlightStyle.OUTLINE, ColorUtil.withAlpha(c, 140));
	}
}
