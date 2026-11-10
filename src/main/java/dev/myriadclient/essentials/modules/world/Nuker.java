package dev.myriadclient.essentials.modules.world;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.BlockBreakEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.render.RenderLook;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.BreakingSettings;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.BlockInfo;
import dev.myriadclient.api.util.Positions;
import dev.myriadclient.api.util.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Breaks the blocks around you, through the shared breaking service: it picks which, the service mines them (the best
 * tool swapped in, timed for the server, instant blocks several a tick, two at once with Double Break where blocks
 * finish early). Nearest first, except that a block under sand or gravel that's going too waits for it, so a column is
 * taken from the top instead of falling into the hole below.
 * <p>
 * Floor keeps what you stand on (Keep), lets you dig down by sneaking (Sneak), or takes it all (Dig). Ahead Only keeps it
 * to the half in front of where you look, for clearing a way as you walk or fly. Filter picks what goes: anything, only
 * the blocks listed, or anything except them; with Pick Bind held, hitting a block adds its kind to the list (or takes
 * it off). Blocks a fluid would flow into the space of are left (Avoid Fluids), and so are chests, shulker boxes and the
 * like (Spare Storage).
 */
public class Nuker extends Module {
	public enum Floor {
		KEEP, SNEAK, DIG
	}

	public enum Filter {
		ANY, ONLY, EXCEPT
	}

	/** At most this many queued at a time, nearest first: enough for the service to break instant blocks in batches. */
	private static final int QUEUED = 32;
	/** Whatever's being mined: the block fills in as it breaks. */
	private static final RenderLook LOOK = RenderLook.of(HighlightStyle.OUTLINE.withOutlineWidth(1).withFill(HighlightStyle.Fill.SOLID).withFillOpacity(0.5f), 1);

	private final BreakingSettings breaking = new BreakingSettings(sgGeneral);
	private final DoubleSetting range = sgGeneral.decimal("Range").description("How far from your eyes, to the block's nearest point (no further than "
		+ "the server lets you reach).").defaultValue(4.5).range(1, 6).decimals(1).build();
	private final EnumSetting<Floor> floor = sgGeneral.oneOf("Floor", Floor.KEEP).description("Keep: nothing below your feet. Sneak: below your feet "
		+ "too while you sneak, to dig down. Dig: everything.").build();
	private final BoolSetting aheadOnly = sgGeneral.bool("Ahead Only").description("Only blocks in front of where you look (sideways), for clearing a way "
		+ "as you go.").build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Face each block when starting and finishing, for servers that check.").build();

	private final SettingGroup sgFilter = settings.group("Filter");
	private final EnumSetting<Filter> filter = sgFilter.oneOf("Filter", Filter.ANY).description("Any: every block. Only: just the blocks listed. "
		+ "Except: everything but them.").onChanged(v -> cancelAll()).build();
	private final RegistryListSetting<Block> blocks = sgFilter.blocks("Blocks").description("For Only and Except. Or hold Pick Bind and hit a block.")
		.visible(() -> filter.get() != Filter.ANY).onChanged(v -> cancelAll()).build();
	private final KeybindSetting pickBind = sgFilter.keybind("Pick Bind").description("Hold and hit a block to add its kind to Blocks, or take it off "
		+ "(Filter goes to Only if it was Any).").build();
	private final BoolSetting avoidFluids = sgFilter.bool("Avoid Fluids").description("Leave blocks with water or lava beside or above, which would "
		+ "flow into the space.").defaultValue(true).build();
	private final BoolSetting spareStorage = sgFilter.bool("Spare Storage").description("Leave chests, barrels, shulker boxes and other containers.")
		.defaultValue(true).build();

	private final SettingGroup sgRender = settings.group("Render");
	private final BoolSetting render = sgRender.bool("Highlight").description("Show the blocks being mined, filling in as they break.").defaultValue(true).build();
	private final ColorSetting color = sgRender.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT, 140)).visible(render::get).build();

	/** What this has queued and the service hasn't finished. */
	private final Set<BlockPos> queued = new HashSet<>();

	public Nuker() {
		super(Tags.WORLD, "Nuker", "Breaks the blocks around you.");
	}

	@Override
	protected void onDisable() {
		queued.clear();
	}

	@Override
	public String hudInfo() {
		return queued.isEmpty() ? null : String.valueOf(queued.size());
	}

	/** A filter change drops what's waiting under the old one (blocks already being mined finish). */
	private void cancelAll() {
		if (!isEnabled() || !Myriad.isReady()) return;
		for (BlockPos p : queued) if (Myriad.breaking().progress(p) == 0) Myriad.breaking().cancel(p);
		queued.removeIf(p -> !Myriad.breaking().isPending(p));
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Post e) {
		queued.removeIf(p -> !Myriad.breaking().isPending(p));
		if (mc.player.isCreative() || mc.player.isSpectator() || filter.get() == Filter.ONLY && blocks.get().isEmpty()) {
			cancelAll();
			return;
		}
		Breaking.Options options = options();
		Vec3 eyes = Reach.eyes();
		double reach = options.range();
		// Nearest first (the sphere's order); under sand or gravel that's itself to go, only once that's gone.
		List<BlockPos> wanted = Positions.sphere(eyes, reach + 0.87, p -> wanted(p, reach) && !(falls(p.above()) && wanted(p.above(), reach)));
		if (wanted.size() > QUEUED) wanted = new ArrayList<>(wanted.subList(0, QUEUED));

		// What's no longer wanted (out of range, behind you, filtered) and hasn't started is dropped.
		Set<BlockPos> keep = new HashSet<>(wanted);
		for (BlockPos p : queued) if (!keep.contains(p) && Myriad.breaking().progress(p) == 0) Myriad.breaking().cancel(p);
		queued.removeIf(p -> !Myriad.breaking().isPending(p));

		int priority = wanted.size();
		for (BlockPos p : wanted) {
			Breaking.Attempt a = Myriad.breaking().breakBlock(this, p, priority--, options);
			if (a.accepted()) queued.add(a.pos());
		}
	}

	/** Sand, gravel and the like, which fall once what's under them goes. */
	private boolean falls(BlockPos p) {
		return mc.level.getBlockState(p).getBlock() instanceof FallingBlock;
	}

	private boolean wanted(BlockPos p, double reach) {
		BlockState state = mc.level.getBlockState(p);
		if (state.isAir() || state.getFluidState().isSource() && state.getCollisionShape(mc.level, p).isEmpty()) return false;
		if (!Reach.canReach(p, reach) || !BlockInfo.canBreak(p)) return false;
		if (!belowAllowed() && p.getY() < mc.player.getBlockY()) return false;
		if (aheadOnly.get() && !ahead(p)) return false;
		switch (filter.get()) {
			case ONLY -> {
				if (!blocks.get().contains(state.getBlock())) return false;
			}
			case EXCEPT -> {
				if (blocks.get().contains(state.getBlock())) return false;
			}
			case ANY -> {
			}
		}
		if (spareStorage.get() && BlockInfo.isStorage(state)) return false;
		return !avoidFluids.get() || !nextToFluid(p);
	}

	private boolean belowAllowed() {
		return switch (floor.get()) {
			case KEEP -> false;
			case SNEAK -> mc.player.isShiftKeyDown();
			case DIG -> true;
		};
	}

	/** In front of the way you look, sideways: the centre's past the plane through your feet across it. */
	private boolean ahead(BlockPos p) {
		Vec3 look = Vec3.directionFromRotation(0, mc.player.getYRot());
		return (p.getX() + 0.5 - mc.player.getX()) * look.x + (p.getZ() + 0.5 - mc.player.getZ()) * look.z > 0;
	}

	/** Water or lava above it or beside it, which would flow in once it's gone (or in it, waterlogged). */
	private boolean nextToFluid(BlockPos p) {
		if (!mc.level.getFluidState(p).isEmpty()) return true;
		for (Direction d : Direction.values()) {
			if (d != Direction.DOWN && !mc.level.getFluidState(p.relative(d)).isEmpty()) return true;
		}
		return false;
	}

	private Breaking.Options options() {
		// No swings on screen (the swing each packet needs is sent either way); the block stays until the server says it's gone, so a refused
		// finish never leaves you walking into a block that's only gone on your side.
		return breaking.options().withRotate(rotate.get()).withSwing(false).withRange(Math.min(range.get(), Reach.blockRange())).withWaitForServer(true);
	}

	/** With Pick Bind held, a hit adds the block's kind to Blocks or takes it off, and mines nothing. */
	@Subscribe
	private void onHit(BlockBreakEvent.Start e) {
		if (!inGame() || !pickBind.get().isPressed()) return;
		e.cancel();
		Block block = mc.level.getBlockState(e.pos()).getBlock();
		if (block.defaultBlockState().isAir()) return;
		String name = block.getName().getString();
		if (blocks.contains(block)) {
			blocks.remove(block);
			info("No longer " + (filter.get() == Filter.EXCEPT ? "sparing " : "nuking ") + name);
			return;
		}
		if (filter.get() == Filter.ANY) filter.set(Filter.ONLY);
		blocks.add(block);
		info((filter.get() == Filter.EXCEPT ? "Sparing " : "Nuking ") + name);
	}

	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		if (!render.get()) return;
		for (BlockPos p : queued) {
			float progress = Myriad.breaking().progress(p);
			if (progress > 0) e.box(new AABB(p).deflate(0.5 - 0.5 * Math.min(1, progress)), LOOK, color.argb());
		}
	}
}
