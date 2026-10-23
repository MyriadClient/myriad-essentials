package dev.myriadclient.essentials.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.essentials.modules.world.PortalBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Development only: {@code .portaltest [strict|2b2t]} builds a Nether portal with Portal Builder on a stone floor in
 * front of you in singleplayer and reports how many ticks it took until it was lit. {@code strict}: Rotate on, no air
 * placement; {@code 2b2t}: Rotate off, Air Place on, no corners.
 */
public final class PortalTest extends Command {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad Essentials");
	private static final int Y = 220;

	private BlockPos base;
	private int ticks;
	private boolean started, tap;

	public PortalTest() {
		super("portaltest", "Development: times Portal Builder from click to lit portal.");
	}

	@Override
	public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
		b.then(literal("strict").executes(ctx -> start(true)));
		b.then(literal("2b2t").executes(ctx -> start(false)));
		b.then(literal("tap").executes(ctx -> {
			tap = true;
			return start(false);
		}));
	}

	@SuppressWarnings("unchecked")
	private int start(boolean strict) {
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			error("Only in singleplayer.");
			return SINGLE_SUCCESS;
		}
		PortalBuilder pb = Modules.get(PortalBuilder.class);
		if (pb.isEnabled()) pb.disable();
		((Setting<PortalBuilder.Trigger>) pb.settings.get("trigger").orElseThrow()).set(tap ? PortalBuilder.Trigger.ENABLE : PortalBuilder.Trigger.CLICK);
		set(pb, "rotate", strict);
		set(pb, "air_place", !strict);
		set(pb, "corners", strict);
		if (!pb.isEnabled() && !tap) pb.enable();
		BlockPos feet = new BlockPos(mc.player.getBlockX(), Y, mc.player.getBlockZ());
		// The frame stands on the floor two blocks south of you, across your view.
		base = feet.offset(0, 0, 2);
		MinecraftServer server = mc.getSingleplayerServer();
		var dimension = mc.player.level().dimension();
		var uuid = mc.player.getUUID();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			if (level == null || p == null) return;
			for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-6, -1, -4), feet.offset(6, 7, 6))) {
				level.setBlock(pos, pos.getY() == Y - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
			p.setGameMode(GameType.SURVIVAL);
			p.getInventory().clearContent();
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			p.getInventory().add(new ItemStack(Items.OBSIDIAN, 64));
			p.getInventory().add(new ItemStack(Items.FLINT_AND_STEEL));
			p.teleportTo(level, feet.getX() + 0.5, Y, feet.getZ() + 0.5, Set.of(), 0, 20, true);
		});
		ticks = -20;
		started = false;
		Myriad.events().subscribe(this);
		return SINGLE_SUCCESS;
	}

	/** Whether there's a lit portal anywhere around you. */
	private boolean portalNear() {
		for (BlockPos p : BlockPos.betweenClosed(base.offset(-6, -1, -4), base.offset(6, 6, 6))) if (mc.level.getBlockState(p).is(Blocks.NETHER_PORTAL)) return true;
		return false;
	}

	@SuppressWarnings("unchecked")
	private static void set(PortalBuilder pb, String id, boolean value) {
		((Setting<Boolean>) pb.settings.get(id).orElseThrow()).set(value);
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (mc.player == null) {
			Myriad.events().unsubscribe(this);
			return;
		}
		ticks++;
		if (ticks == 0 && !started) {
			started = true;
			// A tap: the bind turns it on, it builds in front of you and turns itself off.
			if (tap) Modules.get(PortalBuilder.class).enable();
			else Modules.get(PortalBuilder.class).build(base, Direction.SOUTH);
			return;
		}
		if (!started) return;
		// A tap is over when the module has turned itself off; then there should be a portal somewhere in front.
		if (tap && Modules.get(PortalBuilder.class).isEnabled() && ticks < 400) return;
		boolean lit = tap ? portalNear() : mc.level.getBlockState(base.above()).is(Blocks.NETHER_PORTAL);
		if (!lit && ticks < 400) return;
		String line = (lit ? "lit after " + ticks + " ticks (" + String.format("%.2f", ticks / 20.0) + " s)" : "not lit after 400 ticks")
			+ "; off hand " + mc.player.getOffhandItem();
		if (tap) line += "; module " + (Modules.get(PortalBuilder.class).isEnabled() ? "still on" : "turned itself off");
		tap = false;
		LOG.info("[portaltest] {}", line);
		var build = Modules.get(PortalBuilder.class).build();
		if (!lit && build != null) for (var step : build.steps()) LOG.info("[portaltest]   {} {} {}", step.pos().toShortString(), step.target(), step.status());
		info(line);
		Myriad.events().unsubscribe(this);
	}
}
