package dev.myriadclient.essentials.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each villager sells, by its UUID, kept per server in villager-trades.json: the server only sends a villager's
 * trades with its trading screen, so once one's been opened its trades are known without opening it again. An entry
 * holds while the villager has the profession and level it had then: a new level brings new trades (someone trading with
 * it while you were away included, once its level shows), and a villager seen without a job (its workstation gone) is
 * forgotten, its next trades being new. {@link #forgetAll} starts over for a server. Prices and stock change as it trades and
 * restocks, so they're as last seen; the trades themselves don't. Forgotten once unseen for {@link #FORGET_MS}.
 */
public final class VillagerTrades {
	private static final String FILE = "villager-trades.json";
	/** A villager unseen this long (a month) is forgotten. */
	private static final long FORGET_MS = 30L * 24 * 60 * 60 * 1000;
	/** How often a change is saved at most, and how often nearby villagers are looked at for a lost job. */
	private static final long SAVE_MS = 10_000;
	private static final int WATCH_TICKS = 20;

	/**
	 * One trade: what it gives (item id, and a book's enchantments by id), what it costs (its first payment and price, and
	 * a second payment, a book for a book say: "" and 0 if none), and stock.
	 */
	public record Trade(String item, Map<String, Integer> enchantments, String payment, int price, String paymentB, int priceB, boolean soldOut) {
		/** Whether it gives {@code item} (and, if {@code enchantment} isn't null, a book with it at {@code minLevel} or more). */
		public boolean gives(String item, @Nullable String enchantment, int minLevel) {
			if (!this.item.equals(item)) return false;
			return enchantment == null || enchantments.getOrDefault(enchantment, 0) >= minLevel;
		}
	}

	/** A villager's trades as last seen, with the profession and level it had. */
	public record Known(String profession, int level, List<Trade> trades, long seen) {}

	private static @Nullable AddonStorage storage;
	/** By server, then villager. */
	private static final Map<String, Map<UUID, Known>> known = new HashMap<>();
	private static boolean dirty;
	private static long savedAt;

	private VillagerTrades() {
	}

	/** Reads what's known, and starts watching villagers for lost jobs. */
	public static void load(AddonStorage storage) {
		VillagerTrades.storage = storage;
		long now = System.currentTimeMillis();
		storage.readJson(FILE).filter(JsonElement::isJsonObject).ifPresent(json -> {
			for (var server : json.getAsJsonObject().entrySet()) {
				if (!server.getValue().isJsonObject()) continue;
				Map<UUID, Known> villagers = new HashMap<>();
				for (var v : server.getValue().getAsJsonObject().entrySet()) {
					try {
						Known k = read(v.getValue().getAsJsonObject());
						if (now - k.seen() < FORGET_MS) villagers.put(UUID.fromString(v.getKey()), k);
					} catch (RuntimeException ignored) {
						// An entry that isn't one is dropped.
					}
				}
				known.put(server.getKey(), villagers);
			}
		});
		Myriad.events().subscribe(new Watch());
	}

	private static Map<UUID, Known> here() {
		String key = Myriad.server().serverKey();
		return known.computeIfAbsent(key != null ? key : "unknown", k -> new HashMap<>());
	}

	private static String profession(Villager v) {
		return v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:none");
	}

	/** What {@code v} sells, if it's known and still holds (same profession and level as then); null if it has to be opened. */
	public static @Nullable Known of(Villager v) {
		Known k = here().get(v.getUUID());
		if (k == null || !k.profession().equals(profession(v)) || k.level() != v.getVillagerData().level()) return null;
		return k;
	}

	/** {@code v}'s trades as its trading screen shows them now. */
	public static void record(Villager v, MerchantOffers offers) {
		List<Trade> trades = new ArrayList<>();
		for (MerchantOffer o : offers) {
			ItemStack result = o.getResult();
			Map<String, Integer> enchantments = new LinkedHashMap<>();
			ItemEnchantments stored = result.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
			for (var e : stored.entrySet()) e.getKey().unwrapKey().ifPresent(key -> enchantments.put(key.identifier().toString(), e.getIntValue()));
			ItemStack b = o.getCostB();
			trades.add(new Trade(BuiltInRegistries.ITEM.getKey(result.getItem()).toString(), enchantments,
				BuiltInRegistries.ITEM.getKey(o.getCostA().getItem()).toString(), o.getCostA().getCount(),
				b.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(b.getItem()).toString(), b.isEmpty() ? 0 : b.getCount(), o.isOutOfStock()));
		}
		here().put(v.getUUID(), new Known(profession(v), v.getVillagerData().level(), trades, System.currentTimeMillis()));
		dirty = true;
		saveSoon();
	}

	/** Forgets every villager known on this server (one traded with and changed while you were away, say); how many it was. */
	public static int forgetAll() {
		Map<UUID, Known> here = here();
		int n = here.size();
		here.clear();
		dirty = true;
		save();
		return n;
	}

	/** Forgets what {@code v} sells (its trades are about to change). */
	public static void forget(Villager v) {
		if (here().remove(v.getUUID()) != null) {
			dirty = true;
			saveSoon();
		}
	}

	private static void saveSoon() {
		if (dirty && System.currentTimeMillis() - savedAt > SAVE_MS) save();
	}

	/** Writes what's changed. */
	public static void save() {
		if (!dirty || storage == null) return;
		dirty = false;
		savedAt = System.currentTimeMillis();
		JsonObject json = new JsonObject();
		known.forEach((server, villagers) -> {
			if (villagers.isEmpty()) return;
			JsonObject s = new JsonObject();
			villagers.forEach((id, k) -> s.add(id.toString(), write(k)));
			json.add(server, s);
		});
		storage.writeJson(FILE, json);
	}

	private static JsonObject write(Known k) {
		JsonObject o = new JsonObject();
		o.addProperty("profession", k.profession());
		o.addProperty("level", k.level());
		o.addProperty("seen", k.seen());
		JsonArray trades = new JsonArray();
		for (Trade t : k.trades()) {
			JsonObject e = new JsonObject();
			e.addProperty("item", t.item());
			e.addProperty("payment", t.payment());
			e.addProperty("price", t.price());
			e.addProperty("soldOut", t.soldOut());
			if (!t.paymentB().isEmpty()) {
				e.addProperty("paymentB", t.paymentB());
				e.addProperty("priceB", t.priceB());
			}
			if (!t.enchantments().isEmpty()) {
				JsonObject en = new JsonObject();
				t.enchantments().forEach(en::addProperty);
				e.add("enchantments", en);
			}
			trades.add(e);
		}
		o.add("trades", trades);
		return o;
	}

	private static Known read(JsonObject o) {
		List<Trade> trades = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray("trades")) {
			JsonObject e = el.getAsJsonObject();
			Map<String, Integer> enchantments = new LinkedHashMap<>();
			if (e.has("enchantments")) for (var en : e.getAsJsonObject("enchantments").entrySet()) enchantments.put(en.getKey(), en.getValue().getAsInt());
			trades.add(new Trade(e.get("item").getAsString(), enchantments, e.get("payment").getAsString(), e.get("price").getAsInt(),
				e.has("paymentB") ? e.get("paymentB").getAsString() : "", e.has("priceB") ? e.get("priceB").getAsInt() : 0, e.get("soldOut").getAsBoolean()));
		}
		return new Known(o.get("profession").getAsString(), o.get("level").getAsInt(), trades, o.get("seen").getAsLong());
	}

	/** Forgets villagers seen without a job (their next trades are new), every second; saves on leaving the world. */
	private static final class Watch {
		private int ticks;

		@Subscribe
		private void onTick(TickEvent.Post e) {
			var mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null || ++ticks % WATCH_TICKS != 0) return;
			Map<UUID, Known> here = here();
			if (here.isEmpty()) return;
			for (Villager v : mc.level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(64))) {
				if (v.getVillagerData().profession().is(VillagerProfession.NONE) && here.remove(v.getUUID()) != null) dirty = true;
			}
			saveSoon();
		}

		@Subscribe
		private void onLeave(WorldEvent.Leave e) {
			save();
		}
	}
}
