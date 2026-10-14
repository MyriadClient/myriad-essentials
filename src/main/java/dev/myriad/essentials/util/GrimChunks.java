package dev.myriad.essentials.util;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which chunks Grim (2b2t) knows the client has. Grim sends one of its pings after each chunk and only counts the chunk
 * as loaded on the client once that ping is answered; standing in a chunk it doesn't count yet, you're set back. Pings
 * are normally answered at once, so this only matters while {@link GlideHold} holds the answers back: a chunk that
 * arrives meanwhile stays unknown to Grim until they go out. Flying fast into chunks the server sends just in time (2b2t
 * in new terrain), a hold can run into one.
 * <p>
 * Pings are numbered as they arrive; each chunk keeps the number of the last ping before it, and is known once a later
 * ping has been answered. Chunks that came before {@link #start} are taken as known.
 */
public final class GrimChunks {
	private static final GrimChunks INSTANCE = new GrimChunks();

	/** Network thread: pings so far, and for each chunk the count when it arrived. */
	private volatile int pings;
	private final Map<Long, Integer> chunkAfter = new ConcurrentHashMap<>();
	/** Ping id to its number, until answered. */
	private final Map<Integer, Integer> pingNumber = new ConcurrentHashMap<>();
	/** The highest numbered ping answered. */
	private volatile int answered;
	private int users;

	private GrimChunks() {
	}

	public static void start() {
		if (INSTANCE.users++ == 0) Myriad.events().subscribe(INSTANCE);
	}

	public static void stop() {
		if (INSTANCE.users == 0 || --INSTANCE.users > 0) return;
		Myriad.events().unsubscribe(INSTANCE);
		INSTANCE.clear();
	}

	/** Whether the client has the chunk at block {@code x, z} and Grim knows it does. */
	public static boolean known(double x, double z) {
		return why(x, z) == null;
	}

	/** Why Grim doesn't count the chunk at block {@code x, z} as the client's: "missing", "unanswered"; null if it does. */
	public static String why(double x, double z) {
		int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
		var level = Minecraft.getInstance().level;
		if (level == null || !level.hasChunk(cx, cz)) return "missing";
		Integer after = INSTANCE.chunkAfter.get(ChunkPos.pack(cx, cz));
		return after == null || INSTANCE.answered > after ? null : "unanswered";
	}

	private void clear() {
		chunkAfter.clear();
		pingNumber.clear();
		pings = answered = 0;
	}

	@Subscribe(priority = Priority.HIGHEST, packets = {ClientboundPingPacket.class, ClientboundLevelChunkWithLightPacket.class, ClientboundForgetLevelChunkPacket.class})
	private void onReceive(PacketEvent.Receive e) {
		switch (e.packet()) {
			case ClientboundPingPacket ping -> {
				pings++;
				if (pingNumber.size() > 1024) pingNumber.clear();
				pingNumber.put(ping.getId(), pings);
			}
			case ClientboundLevelChunkWithLightPacket chunk -> chunkAfter.put(ChunkPos.pack(chunk.getX(), chunk.getZ()), pings);
			case ClientboundForgetLevelChunkPacket forget -> chunkAfter.remove(forget.pos().pack());
			default -> {
			}
		}
	}

	@Subscribe(priority = Priority.LOWEST, packets = ServerboundPongPacket.class)
	private void onSend(PacketEvent.Send e) {
		if (e.isCancelled() || !(e.packet() instanceof ServerboundPongPacket pong)) return;
		Integer n = pingNumber.remove(pong.getId());
		if (n != null && n > answered) answered = n;
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		clear();
	}
}
