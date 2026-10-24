package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InteractEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.Reach;
import dev.myriadclient.essentials.modules.render.BlockHighlight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places blocks in mid-air: with a block in hand and nothing under the crosshair, right-click puts it where you look,
 * {@link #distance} blocks away, outlined beforehand (in Block Highlight's look while that's on). Farther than the server would accept, it comes back along your
 * look to the farthest space that is: your look has to enter the space within reach, so up to about 5 blocks ahead
 * (6 looking along a diagonal).
 */
public class AirPlace extends Module {
	private final DoubleSetting distance = sgGeneral.decimal("Distance").description("How far in front of you the block goes.")
		.defaultValue(3).range(1, 6).decimals(1).build();
	private final SettingGroup sgRender = settings.group("Render");
	private final BoolSetting showTarget = sgRender.bool("Show Target").description("Where the block will go (Block Highlight shows it instead while it's on).")
		.defaultValue(true).build();
	private final ColorSetting color = sgRender.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(showTarget::get).build();
	private final RenderSettings look = RenderSettings.builder(sgRender).visible(showTarget::get)
		.defaults(HighlightStyle.OUTLINE.withFill(HighlightStyle.Fill.NONE)).throughWalls(false).build();

	/** How far back along the look each try steps when the space is out of reach. */
	private static final double STEP = 0.2;

	/** Where a right-click would place, worked out once a tick; null if vanilla should handle it. */
	private BlockPos target;

	public AirPlace() {
		super(Tags.WORLD, "Air Place", "Place blocks in mid-air where you look.");
	}

	private Placement.Options options() {
		return Placement.Options.DEFAULT.withAirPlace(true).withRange(Reach.blockRange());
	}

	/**
	 * Where a right-click would place now: the space {@link #distance} along your look, or the farthest one before it
	 * that can be placed in. Null if vanilla should handle it (or nothing along the look will do).
	 */
	private BlockPos findTarget() {
		if (!inGame() || !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return null;
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return null;
		Vec3 eyes = mc.player.getEyePosition(), look = mc.player.getViewVector(1);
		BlockPos tried = null;
		for (double d = distance.get(); d >= 1; d -= STEP) {
			BlockPos pos = BlockPos.containing(eyes.add(look.scale(d)));
			if (pos.equals(tried)) continue;
			tried = pos;
			if (Myriad.placement().check(pos, options()).ok()) return pos;
		}
		return null;
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		target = findTarget();
	}

	/** Where a right-click would place now, or null (also while this is off): Block Highlight outlines it. */
	public static BlockPos target() {
		AirPlace m = Modules.active(AirPlace.class);
		return m == null ? null : m.target;
	}

	@Override
	protected void onDisable() {
		target = null;
	}

	@Subscribe
	private void onUse(InteractEvent.Item e) {
		if (e.hand() != InteractionHand.MAIN_HAND) return;
		// The look may have moved since the tick started.
		BlockPos pos = findTarget();
		if (pos == null) return;
		e.cancel();
		Myriad.placement().place(this, pos, mc.player.getInventory().getSelectedSlot(), options());
	}

	/** Where the block will go, unless Block Highlight shows it in its own look. */
	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		BlockPos pos = target;
		if (pos != null && showTarget.get() && Modules.active(BlockHighlight.class) == null) e.box(new AABB(pos), look, color.argb());
	}
}
