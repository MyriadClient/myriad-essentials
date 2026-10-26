package dev.myriadclient.essentials.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.addon.AddonStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who threw each ender pearl seen, by the pearl's UUID, kept per server in pearl-owners.json: the client knows a pearl's
 * owner only while the owner is loaded near you, but a pearl keeps its UUID, so one in a stasis chamber still names its
 * owner once they've gone, and after you leave and come back. Forgotten once unseen for FORGET_MS.
 */
public final class PearlOwners {
	private static final String FILE = "pearl-owners.json";
	/** A pearl unseen this long (a month) is forgotten: thrown and landed, or the chamber gone. */
	private static final long FORGET_MS = 30L * 24 * 60 * 60 * 1000;
	/** How often a change is saved at most. */
	private static final long SAVE_MS = 10_000;

	private record Owner(String name, long seen) {
	}

	private static @Nullable AddonStorage storage;
	/** By server (its address, or the world's name in singleplayer), then pearl. */
	private static final Map<String, Map<UUID, Owner>> owners = new HashMap<>();
	private static boolean dirty;
	private static long savedAt;

	private PearlOwners() {
	}

	public static void load(AddonStorage storage) {
		PearlOwners.storage = storage;
		long now = System.currentTimeMillis();
		storage.readJson(FILE).filter(JsonElement::isJsonObject).ifPresent(json -> {
			for (var server : json.getAsJsonObject().entrySet()) {
				if (!server.getValue().isJsonObject()) continue;
				Map<UUID, Owner> pearls = new HashMap<>();
				for (var pearl : server.getValue().getAsJsonObject().entrySet()) {
					try {
						JsonObject o = pearl.getValue().getAsJsonObject();
						long seen = o.get("seen").getAsLong();
						if (now - seen < FORGET_MS) pearls.put(UUID.fromString(pearl.getKey()), new Owner(o.get("name").getAsString(), seen));
					} catch (RuntimeException ignored) {
						// An entry that isn't one is dropped.
					}
				}
				owners.put(server.getKey(), pearls);
			}
		});
	}

	/** {@code pearl}'s owner's name: who it says, noted for later, or who it said when last known; null if never known. */
	public static @Nullable String owner(ThrownEnderpearl pearl) {
		Map<UUID, Owner> here = owners.computeIfAbsent(server(), k -> new HashMap<>());
		Entity owner = pearl.getOwner();
		long now = System.currentTimeMillis();
		Owner known = here.get(pearl.getUUID());
		if (owner != null) {
			String name = owner.getScoreboardName();
			// Seen again: kept a month from now (noted at most once a minute, so it isn't saved every frame).
			if (known == null || !known.name().equals(name) || now - known.seen() > 60_000) {
				here.put(pearl.getUUID(), new Owner(name, now));
				dirty = true;
			}
			saveSoon();
			return name;
		}
		if (known == null) return null;
		if (now - known.seen() > 60_000) {
			here.put(pearl.getUUID(), new Owner(known.name(), now));
			dirty = true;
		}
		saveSoon();
		return known.name();
	}

	private static void saveSoon() {
		if (dirty && System.currentTimeMillis() - savedAt > SAVE_MS) save();
	}

	/** Writes what's changed (on leaving a world too). */
	public static void save() {
		if (!dirty || storage == null) return;
		dirty = false;
		savedAt = System.currentTimeMillis();
		JsonObject json = new JsonObject();
		owners.forEach((server, pearls) -> {
			if (pearls.isEmpty()) return;
			JsonObject s = new JsonObject();
			pearls.forEach((id, o) -> {
				JsonObject e = new JsonObject();
				e.addProperty("name", o.name());
				e.addProperty("seen", o.seen());
				s.add(id.toString(), e);
			});
			json.add(server, s);
		});
		storage.writeJson(FILE, json);
	}

	private static String server() {
		var mc = Minecraft.getInstance();
		if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT);
		var server = mc.getSingleplayerServer();
		return server != null ? "singleplayer:" + server.getWorldData().getLevelName() : "unknown";
	}
}
