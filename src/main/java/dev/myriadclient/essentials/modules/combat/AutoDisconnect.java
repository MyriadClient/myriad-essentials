package dev.myriadclient.essentials.modules.combat;

import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.combat.Threats;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.item.ItemInfo;
import dev.myriadclient.api.item.Slots;
import dev.myriadclient.api.map.Waypoint;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.ActionSetting;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ChoiceSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.IntSetting;
import dev.myriadclient.api.setting.RegistryListSetting;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.util.Format;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Leaves the server before you die: at low health, after too many totem pops or with too few totems left, when your
 * armour is about to break, when a player you haven't friended or an entity you picked comes close, or when a bed,
 * respawn anchor, end crystal or creeper nearby could kill you; or where you are: below or above a height (Hard Deck), or
 * near a spot, given by coordinates or one of your map's waypoints (Destination), for leaving a flight or a walk running
 * to somewhere. The disconnect screen says why, at what time, and how long ago (counting up, for coming back to it), with
 * a report beside a picture of the screen as you left (DisconnectReport): your health over the last seconds, totems,
 * armour, who was near and what they held, the worst that could hit you, and so on. Never where you were: the screen
 * could be on a stream or in a screenshot.
 * Singleplayer and 2b2t's queue are left alone.
 * <p>
 * Falls aren't among the reasons: the server keeps you where you were, falling, and you'd land as you rejoined. The
 * same goes for leaving in the air by Hard Deck or Destination: you're in the air when you rejoin.
 * <p>
 * Afterwards it either turns itself off, or stays on and waits for the reason it left to clear before that reason can
 * trigger again, so you can rejoin (say with no totems left) without leaving again straight away.
 */
public class AutoDisconnect extends Module {
	public enum AfterLeaving {
		TURN_OFF, WAIT_FOR_CLEAR
	}

	/** Where Destination is: coordinates, or a waypoint of your map's. */
	public enum Target {
		COORDS, WAYPOINT
	}

	/** How the reason it leaves with starts, so the disconnect screen knows it was this (and adds when, and how long ago). */
	public static final String PREFIX = "[Auto Disconnect] ";

	private static final Vec3 QUEUE_SPOT = new Vec3(0.5, 64, 0.5);
	private static final Logger LOG = LoggerFactory.getLogger("Essentials/AutoDisconnect");
	/** The picture of the screen as you left, kept as a texture for the disconnect screen. */
	public static final Identifier SCREENSHOT = Identifier.fromNamespaceAndPath("myriad-essentials", "auto_disconnect/screen");
	/** Ticks of health kept for the report (15 seconds), and how far players count as near in it. */
	private static final int HISTORY = 300, NEAR = 64;
	private static final String[] LEVELS = {"", " II", " III", " IV", " V"};

	/** The report's sections, in order: you, what was around you, and the server. */
	public enum Section {
		YOU, AROUND, CONNECTION
	}

	/** One line of the report: its section, what, what it was, and whether it's what made you leave (or part of it). */
	public record Row(Section section, String label, String value, boolean cause) {
	}

	/**
	 * What it saw as it left: when, why (as the reason said it, and the setting that tripped), the report's rows, and your
	 * health (and its most) over the ticks before, oldest first.
	 */
	public record Report(long at, String reason, String trigger, List<Row> rows, float[] health, float maxHealth) {
	}

	private long leftAt;
	private @Nullable Report report;
	/** The picture's size, once it's arrived (0 until then, or without one), and whether one was taken as you left. */
	private int shotWidth, shotHeight;
	private boolean pictured;
	private final float[] healthHistory = new float[HISTORY];
	private int historyAt, historyCount;
	/** When you joined the server you're on, and that connection (a new one starts the session over). */
	private long joinedAt;
	private @Nullable Object joinedConnection;

	private final SettingGroup sgHealth = settings.group("Health");
	private final BoolSetting health = sgHealth.bool("Health").description("Leave at low health (absorption included).").defaultValue(true).build();
	private final DoubleSetting healthThreshold = sgHealth.decimal("Threshold").description("Leave at this health or less, in half hearts (20 is full health).").defaultValue(6).range(1, 36).decimals(1).visible(health::get).build();
	private final BoolSetting pops = sgHealth.bool("Totem Pops").description("Leave after this many totems pop in one life.").build();
	private final IntSetting popCount = sgHealth.integer("Pops").description("How many pops in one life make you leave.").defaultValue(3).range(1, 20).visible(pops::get).build();
	private final BoolSetting totems = sgHealth.bool("Totems Left").description("Leave when you have this many totems or fewer.").build();
	private final IntSetting totemCount = sgHealth.integer("Totems").description("Leave once you're down to this many (0: once the last one is gone).").defaultValue(0).range(0, 20).visible(totems::get).build();
	private final BoolSetting armor = sgHealth.bool("Armor").description("Leave when a piece of armour you wear is about to break.").build();
	private final IntSetting armorDurability = sgHealth.integer("Armor Durability").description("Durability left, in percent.").defaultValue(10).range(1, 50).visible(armor::get).build();

	private final SettingGroup sgDanger = settings.group("Danger");
	private final BoolSetting players = sgDanger.bool("Players").description("Leave when a player who isn't a friend comes close.").build();
	private final DoubleSetting playerRange = sgDanger.decimal("Player Range").description("How close a player has to come, in blocks.").defaultValue(16).range(1, 128).decimals(0).visible(players::get).build();
	private final BoolSetting beds = sgDanger.bool("Beds").description("Leave when a bed nearby could kill you (outside the Overworld).").defaultValue(true).build();
	private final BoolSetting anchors = sgDanger.bool("Anchors").description("Leave when a charged respawn anchor nearby could kill you (outside the Nether).").defaultValue(true).build();
	private final BoolSetting crystals = sgDanger.bool("Crystals").description("Leave when end crystals nearby could kill you.").build();
	private final BoolSetting creepers = sgDanger.bool("Creepers").description("Leave when a creeper comes close.").build();
	private final DoubleSetting creeperRange = sgDanger.decimal("Creeper Range").description("How close a creeper has to come, in blocks.").defaultValue(5).range(1, 16).decimals(1).visible(creepers::get).build();
	private final RegistryListSetting<EntityType<?>> entities = sgDanger.entityTypes("Entities").description("Leave when one of these comes close (TNT minecarts, withers...).").build();
	private final DoubleSetting entityRange = sgDanger.decimal("Entity Range").description("How close one of those entities has to come, in blocks.").defaultValue(10).range(1, 64).decimals(0).visible(() -> !entities.get().isEmpty()).build();

	private final SettingGroup sgPosition = settings.group("Position");
	private final BoolSetting hardDeck = sgPosition.bool("Hard Deck")
		.description("Leave below Floor Y or above Ceiling Y. Each counts once you've been on its safe side in this dimension, so being "
			+ "outside one when you turn this on or arrive doesn't make you leave. Left in the air, you're in the air when you rejoin.").build();
	private final IntSetting floorY = sgPosition.integer("Floor Y").description("Leave below this height.")
		.defaultValue(64).range(-64, 2000).typed().visible(hardDeck::get).build();
	private final IntSetting ceilingY = sgPosition.integer("Ceiling Y").description("Leave above this height.")
		.defaultValue(500).range(-64, 2000).typed().visible(hardDeck::get).build();
	private final BoolSetting destination = sgPosition.bool("Destination")
		.description("Leave once you're within Within blocks of a spot (sideways, at any height): where a flight or a walk you left "
			+ "running should end. Pick one on its way (on the highway, for Recast).").build();
	private final EnumSetting<Target> target = sgPosition.oneOf("Target", Target.COORDS)
		.description("Coords: X and Z, in the dimension you're in. Waypoint: one of your map's (Xaero's Minimap), found from whichever "
			+ "dimension you're in (a Nether waypoint at an eighth of the Overworld's distance).").visible(destination::get).build();
	private final ChoiceSetting waypoint = sgPosition.choice("Waypoint", AutoDisconnect::waypointNames)
		.description("The waypoint to leave near, from your map's waypoints for this server.")
		.visible(() -> destination.get() && target.get() == Target.WAYPOINT).build();
	private final IntSetting x = sgPosition.integer("X").description("The spot's X.").defaultValue(0).range(-30_000_000, 30_000_000).typed()
		.visible(() -> destination.get() && target.get() == Target.COORDS).build();
	private final IntSetting z = sgPosition.integer("Z").description("The spot's Z.").defaultValue(0).range(-30_000_000, 30_000_000).typed()
		.visible(() -> destination.get() && target.get() == Target.COORDS).build();
	private final IntSetting within = sgPosition.integer("Within").description("How close to the spot, in blocks.")
		.defaultValue(16).range(1, 1_000_000).typed().visible(destination::get).build();
	private final ActionSetting useHere = sgPosition.action("Use Here", () -> {
		if (!inGame()) return;
		x.set(mc.player.getBlockX());
		z.set(mc.player.getBlockZ());
	}).description("Set X and Z to where you are.").visible(() -> destination.get() && target.get() == Target.COORDS).build();

	private final EnumSetting<AfterLeaving> afterLeaving = sgGeneral.oneOf("After Leaving", AfterLeaving.TURN_OFF)
		.description("Turn off, or stay on and wait for the reason you left to clear before it can make you leave again.").build();
	private final BoolSetting copyCoords = sgGeneral.bool("Copy Coords").description("Copy where you were to the clipboard.").build();
	private final BoolSetting screenshot = sgGeneral.bool("Screenshot")
		.description("Show what was on your screen as you left (HUD and all) on the disconnect screen, beside the report. Kept only until you next leave; never saved.")
		.defaultValue(true).build();

	/** Hard Deck: you've been above Floor Y, and below Ceiling Y, in {@link #deckLevel} (each counts from then). */
	private boolean aboveFloor, belowCeiling;
	private @Nullable Level deckLevel;

	/** Reasons that made you leave and haven't cleared since (Wait For Clear). */
	private final Set<String> waiting = new HashSet<>();

	private record Trigger(BooleanSupplier enabled, Supplier<@Nullable String> check) {
	}

	/** Each reason to leave by key, in the order they're checked: the check returns why, or null. */
	private final Map<String, Trigger> triggers = new LinkedHashMap<>();

	public AutoDisconnect() {
		super(Tags.COMBAT, "Auto Disconnect", "Leaves the server when you're about to die.");
		trigger("health", health::get, () -> Threats.health() <= healthThreshold.get() ? String.format(Locale.ROOT, "health %.1f", Threats.health()) : null);
		trigger("pops", pops::get, () -> {
			int n = Myriad.server().totemPops(mc.player);
			return n >= popCount.get() ? n + " totem pops" : null;
		});
		trigger("totems", totems::get, () -> {
			int n = Myriad.inventory().count(s -> s.is(Items.TOTEM_OF_UNDYING));
			return n <= totemCount.get() ? n + (n == 1 ? " totem" : " totems") + " left" : null;
		});
		trigger("armor", armor::get, this::wornOut);
		trigger("players", players::get, this::nearbyPlayer);
		trigger("beds", beds::get, () -> Threats.beds(8) >= Threats.health() ? "a bed could kill you" : null);
		trigger("anchors", anchors::get, () -> Threats.anchors(8) >= Threats.health() ? "a respawn anchor could kill you" : null);
		trigger("crystals", crystals::get, () -> Threats.crystals(12, true) >= Threats.health() ? "end crystals could kill you" : null);
		trigger("creepers", creepers::get, () -> {
			var near = mc.level.getEntitiesOfClass(Creeper.class, mc.player.getBoundingBox().inflate(creeperRange.get()));
			return near.isEmpty() ? null : "a creeper " + Math.round(mc.player.distanceTo(near.getFirst())) + " blocks away";
		});
		trigger("entities", () -> !entities.get().isEmpty(), this::nearbyEntity);
		trigger("hard_deck", hardDeck::get, this::outsideDeck);
		trigger("destination", destination::get, this::nearDestination);
		Myriad.events().subscribe(new Watch());
	}

	private void trigger(String key, BooleanSupplier enabled, Supplier<@Nullable String> check) {
		triggers.put(key, new Trigger(enabled, check));
	}

	/**
	 * Version 2 replaced the Auto Disable toggle with After Leaving; version 3 dropped Falls and Void; version 4 named
	 * Coords Destination.
	 */
	@Override
	public int settingsVersion() {
		return 4;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			// Only "off" is saved (on was the default), and staying on means waiting for the reason to clear.
			saved.get("General", "Auto Disable").ifPresent(v -> {
				if (!v.getAsBoolean()) saved.set("General", "After Leaving", new JsonPrimitive("WAIT_FOR_CLEAR"));
			});
			saved.remove("General", "Auto Disable");
		}
		if (fromVersion <= 2) {
			saved.remove("Danger", "Falls");
			saved.remove("Danger", "Void");
		}
		if (fromVersion <= 3) {
			// Coords is Destination's old name (Destination takes a waypoint too, by Target).
			saved.get("Position", "Coords").ifPresent(v -> saved.set("Position", "Destination", v));
			saved.remove("Position", "Coords");
		}
	}

	@Override
	public String hudInfo() {
		return mc.player == null ? null : String.format(Locale.ROOT, "%.1f", Threats.health());
	}

	@Override
	protected void onEnable() {
		waiting.clear();
		deckLevel = null;
	}

	/**
	 * On a server, on or off: when you joined it, and your health over the last seconds, so a report has them however
	 * long the module's been on. One number a tick.
	 */
	private final class Watch {
		@Subscribe
		private void onTick(TickEvent.Post e) {
			if (!inGame() || mc.getConnection() == null || mc.isLocalServer()) return;
			// A new connection (joined, or another server): the session starts over, and the health it's seen with it.
			if (mc.getConnection() != joinedConnection) {
				joinedConnection = mc.getConnection();
				joinedAt = System.currentTimeMillis();
				historyCount = 0;
			}
			healthHistory[historyAt] = Threats.health();
			historyAt = (historyAt + 1) % HISTORY;
			historyCount = Math.min(HISTORY, historyCount + 1);
		}
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (!inGame()) return;
		if (mc.player.isCreative() || mc.player.isSpectator() || mc.isLocalServer() || mc.getConnection() == null || inQueue()) return;
		String leaveFor = null, leaveKey = null;
		for (Map.Entry<String, Trigger> t : triggers.entrySet()) {
			String reason = t.getValue().enabled.getAsBoolean() ? t.getValue().check.get() : null;
			if (reason == null) {
				// Cleared: it can trigger again.
				waiting.remove(t.getKey());
			} else if (leaveFor == null && !waiting.contains(t.getKey())) {
				leaveFor = reason;
				leaveKey = t.getKey();
			}
		}
		if (leaveFor != null) leave(leaveKey, leaveFor);
	}

	/** In 2b2t's queue: a world of its own that holds you at 0, 64, 0 until your turn. Nothing there can hurt you. */
	private boolean inQueue() {
		return Myriad.server().onHost("2b2t.org") && mc.player.position().distanceTo(QUEUE_SPOT) < 1;
	}

	private @Nullable String wornOut() {
		for (EquipmentSlot slot : Slots.ARMOR) {
			ItemStack s = mc.player.getItemBySlot(slot);
			if (s.isDamageableItem() && ItemInfo.durabilityFraction(s) * 100 < armorDurability.get()) return s.getHoverName().getString() + " is about to break";
		}
		return null;
	}

	private @Nullable String nearbyPlayer() {
		for (Player p : mc.level.players()) {
			if (p == mc.player || !p.isAlive() || p.isSpectator() || Myriad.friends().isFriend(p)) continue;
			if (mc.player.distanceTo(p) <= playerRange.get()) return p.getGameProfile().name() + " " + Math.round(mc.player.distanceTo(p)) + " blocks away";
		}
		return null;
	}

	/** Within Within blocks of Destination's spot (sideways), or null; null too while its waypoint isn't known here. */
	private @Nullable String nearDestination() {
		Vec3 spot;
		String what;
		if (target.get() == Target.WAYPOINT) {
			var w = Myriad.maps().waypoint(waypoint.get());
			if (w.isEmpty()) return null;
			spot = Myriad.maps().positionHere(w.get());
			what = "waypoint " + w.get().name();
		} else {
			spot = new Vec3(x.get() + 0.5, 0, z.get() + 0.5);
			// Not its coordinates: the disconnect screen shows the reason, and a stream or a screenshot would give the
			// place away.
			what = "your destination";
		}
		if (spot == null) return null;
		double d = Math.hypot(mc.player.getX() - spot.x, mc.player.getZ() - spot.z);
		return d <= within.get() ? String.format(Locale.ROOT, "%d blocks from %s", Math.round(d), what) : null;
	}

	/** Your map's waypoints for this server, by name, this dimension's first. */
	private static List<String> waypointNames() {
		return Myriad.maps().waypoints().stream().map(Waypoint::name).distinct().toList();
	}

	/** Past a Hard Deck limit you've been inside of in this dimension (a new world or dimension starts over). */
	private @Nullable String outsideDeck() {
		if (mc.level != deckLevel) {
			deckLevel = mc.level;
			aboveFloor = belowCeiling = false;
		}
		double y = mc.player.getY();
		if (y >= floorY.get()) aboveFloor = true;
		else if (aboveFloor) return String.format(Locale.ROOT, "below the hard deck (Y %.0f, Floor Y %d)", y, floorY.get());
		if (y <= ceilingY.get()) belowCeiling = true;
		else if (belowCeiling) return String.format(Locale.ROOT, "above the hard deck (Y %.0f, Ceiling Y %d)", y, ceilingY.get());
		return null;
	}

	private @Nullable String nearbyEntity() {
		Set<EntityType<?>> types = entities.get();
		for (Entity en : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(entityRange.get()), en -> types.contains(en.getType()))) {
			return en.getName().getString() + " " + Math.round(mc.player.distanceTo(en)) + " blocks away";
		}
		return null;
	}

	/**
	 * When this module last made you leave ({@code System.currentTimeMillis}), 0 if never: Auto Reconnect doesn't go
	 * back after it.
	 */
	public long leftAt() {
		return leftAt;
	}

	/** What it saw as it last made you leave, or null. */
	public @Nullable Report report() {
		return report;
	}

	/** The picture of the screen as you last left ({@link #SCREENSHOT}): its width and height, 0 while there isn't one. */
	public int shotWidth() {
		return shotWidth;
	}

	/** Whether a picture was taken as you last left (it may still be on its way: {@link #shotWidth} 0). */
	public boolean pictured() {
		return pictured;
	}

	public int shotHeight() {
		return shotHeight;
	}

	private void leave(String key, String reason) {
		leftAt = System.currentTimeMillis();
		report = report(key, reason);
		shotWidth = shotHeight = 0;
		pictured = screenshot.get();
		if (pictured) capture();
		if (copyCoords.get()) mc.keyboardHandler.setClipboard(String.format(Locale.ROOT, "%d %d %d", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()));
		if (afterLeaving.get() == AfterLeaving.TURN_OFF) disable();
		else waiting.add(key);
		mc.getConnection().getConnection().disconnect(Component.literal(PREFIX + reason));
	}

	/** The screen as it was last drawn (this tick, before anything else is), into {@link #SCREENSHOT} once it's read back. */
	private void capture() {
		try {
			RenderTarget target = mc.gameRenderer.mainRenderTarget();
			int down = target.width % 2 == 0 && target.height % 2 == 0 ? 2 : 1;
			Screenshot.takeScreenshot(target, down, image -> mc.execute(() -> keep(image)));
		} catch (RuntimeException ex) {
			LOG.warn("Couldn't take the screen's picture", ex);
		}
	}

	private void keep(NativeImage image) {
		// Registering it in the old one's place frees the old one.
		mc.getTextureManager().register(SCREENSHOT, new DynamicTexture(() -> "Auto Disconnect screen", image));
		shotWidth = image.getWidth();
		shotHeight = image.getHeight();
	}

	private Report report(String key, String reason) {
		var p = mc.player;
		List<Row> rows = new ArrayList<>();
		float abs = p.getAbsorptionAmount();
		rows.add(new Row(Section.YOU, "Health", String.format(Locale.ROOT, "%.1f of %.0f", p.getHealth(), p.getMaxHealth()) + (abs > 0 ? String.format(Locale.ROOT, ", %.1f absorption", abs) : ""), key.equals("health")));
		int totems = Myriad.inventory().count(st -> st.is(Items.TOTEM_OF_UNDYING)), popped = Myriad.server().totemPops(p);
		rows.add(new Row(Section.YOU, "Totems", totems + " left, " + popped + " popped", key.equals("pops") || key.equals("totems")));
		rows.add(new Row(Section.YOU, "Armor", armorLeft(), key.equals("armor")));
		rows.add(new Row(Section.YOU, "Food", p.getFoodData().getFoodLevel() + " of 20", false));
		rows.add(new Row(Section.AROUND, "Nearby", nearby(), key.equals("players") || key.equals("entities") || key.equals("creepers")));
		rows.add(new Row(Section.AROUND, "Worst hit", worstHit(), key.equals("beds") || key.equals("anchors") || key.equals("crystals")));
		// The height alone gives no place away; no X or Z.
		rows.add(new Row(Section.AROUND, "Where", dimensionName() + ", Y " + Math.round(p.getY()) + ", " + motion(), key.equals("hard_deck") || key.equals("destination")));
		rows.add(new Row(Section.AROUND, "Speed", String.format(Locale.ROOT, "%.1f blocks/s", Math.hypot(p.getX() - p.xo, p.getZ() - p.zo) * 20), false));
		String effects = effects();
		if (!effects.isEmpty()) rows.add(new Row(Section.YOU, "Effects", effects, false));
		rows.add(new Row(Section.CONNECTION, "Server", "ping " + Myriad.server().ping() + " ms, " + String.format(Locale.ROOT, "%.1f TPS", Myriad.server().tps()), false));
		if (joinedAt > 0) rows.add(new Row(Section.CONNECTION, "Session", Format.duration(leftAt - joinedAt) + " on", false));
		float[] health = new float[historyCount];
		for (int i = 0; i < historyCount; i++) health[i] = healthHistory[(historyAt - historyCount + i + HISTORY) % HISTORY];
		return new Report(leftAt, reason, triggerName(key), List.copyOf(rows), health, p.getMaxHealth() + Math.max(abs, 0));
	}

	/** What made you leave, said with its setting. */
	private String triggerName(String key) {
		return switch (key) {
			case "health" -> String.format(Locale.ROOT, "Health at %.1f or less", healthThreshold.get());
			case "pops" -> popCount.get() + " totem pops";
			case "totems" -> totemCount.get() + (totemCount.get() == 1 ? " totem" : " totems") + " or fewer";
			case "armor" -> "Armour under " + armorDurability.get() + "%";
			case "players" -> String.format(Locale.ROOT, "A player within %.0f blocks", playerRange.get());
			case "beds" -> "A bed could kill you";
			case "anchors" -> "A respawn anchor could kill you";
			case "crystals" -> "End crystals could kill you";
			case "creepers" -> String.format(Locale.ROOT, "A creeper within %.1f blocks", creeperRange.get());
			case "entities" -> String.format(Locale.ROOT, "An entity within %.0f blocks", entityRange.get());
			case "hard_deck" -> "Hard Deck";
			case "destination" -> "Reached your destination";
			default -> key;
		};
	}

	/** The armour piece nearest breaking, with what it has left. */
	private String armorLeft() {
		ItemStack worst = null;
		for (EquipmentSlot slot : Slots.ARMOR) {
			ItemStack s = mc.player.getItemBySlot(slot);
			if (s.isDamageableItem() && (worst == null || ItemInfo.durabilityFraction(s) < ItemInfo.durabilityFraction(worst))) worst = s;
		}
		return worst == null ? "none worn" : worst.getHoverName().getString() + " at " + Math.round(ItemInfo.durabilityFraction(worst) * 100) + "%";
	}

	/** The nearest few players (friends said so), with what they held; or that nobody was near. */
	private String nearby() {
		List<Player> near = new ArrayList<>();
		for (Player o : mc.level.players()) if (o != mc.player && o.isAlive() && !o.isSpectator() && mc.player.distanceTo(o) <= NEAR) near.add(o);
		if (near.isEmpty()) return "no players within " + NEAR + " blocks";
		near.sort(Comparator.comparingDouble(o -> mc.player.distanceTo(o)));
		List<String> said = new ArrayList<>();
		for (Player o : near.subList(0, Math.min(3, near.size()))) {
			String who = o.getGameProfile().name() + " " + Math.round(mc.player.distanceTo(o));
			if (Myriad.friends().isFriend(o)) who += " (friend)";
			else if (!o.getMainHandItem().isEmpty()) who += " (" + o.getMainHandItem().getHoverName().getString() + ")";
			said.add(who);
		}
		return String.join(", ", said) + (near.size() > 3 ? " and " + (near.size() - 3) + " more" : "");
	}

	/** The worst any one thing near could have hit you for, and what. */
	private String worstHit() {
		float crystals = Threats.crystals(12, true), beds = Threats.beds(8), anchors = Threats.anchors(8), creepers = Threats.creepers(8),
			players = Threats.players(5), fall = Threats.fall();
		float worst = Math.max(Math.max(Math.max(crystals, beds), Math.max(anchors, creepers)), Math.max(players, fall));
		if (worst < 0.5f) return "nothing near could hurt you";
		String what = worst == crystals ? "end crystals" : worst == beds ? "a bed" : worst == anchors ? "a respawn anchor" : worst == creepers ? "a creeper"
			: worst == players ? "a player's hit" : "the fall";
		return String.format(Locale.ROOT, "%.1f from %s", worst, what);
	}

	private String dimensionName() {
		var d = mc.level.dimension();
		return d == Level.NETHER ? "Nether" : d == Level.END ? "End" : d == Level.OVERWORLD ? "Overworld" : d.identifier().getPath();
	}

	private String motion() {
		var p = mc.player;
		if (p.isFallFlying()) return "gliding";
		if (p.isPassenger()) return "riding";
		if (p.isInWater()) return "in water";
		return p.onGround() ? "on the ground" : "in the air";
	}

	private String effects() {
		List<String> said = new ArrayList<>();
		for (MobEffectInstance e : mc.player.getActiveEffects()) {
			int amp = e.getAmplifier();
			said.add(e.getEffect().value().getDisplayName().getString() + (amp < LEVELS.length ? LEVELS[amp] : " " + (amp + 1)));
			if (said.size() == 4) break;
		}
		return String.join(", ", said);
	}
}
