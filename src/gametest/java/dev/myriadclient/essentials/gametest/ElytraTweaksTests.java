package dev.myriadclient.essentials.gametest;

import dev.myriadclient.testing.Key;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import net.minecraft.client.Minecraft;

import java.util.Locale;

import static dev.myriadclient.essentials.gametest.ElytraScenes.launch;

/**
 * Elytra Tweaks' Glide Bind (the glide from the ground with one jump, at once in the air) and Air Brake (rocketing at a
 * wall: the hit must not hurt with it, and does without it, so the flight is a real danger).
 */
public final class ElytraTweaksTests extends EssentialsSuite {
	private static final String TWEAKS = "myriad-essentials:elytra_tweaks";

	/** Ticks from now until the client glides, up to {@code max}; -1 if it never does. */
	private static int ticksToGlide(TestContext t, int max) {
		for (int i = 0; i <= max; i++) {
			if (t.onGame(() -> Minecraft.getInstance().player.isFallFlying())) return i;
			t.waitTicks(1);
		}
		return -1;
	}

	@Test(name = "glide_bind_ground", tags = "elytra")
	public void glideBindGround(TestContext t) {
		t.player().give(38, "elytra");
		t.player().at(0.5, 0, 0.5, -90, 0);
		t.waitTicks(10);
		t.flagsFromHere();
		t.module(TWEAKS).set("glide_bind", "g").set("air_brake", "false");
		t.step("key g click");
		int ticks = ticksToGlide(t, 20);
		t.module(TWEAKS).set("glide_bind", "none");
		String detail = ticks < 0 ? "never glided" : "gliding " + ticks + " ticks after the bind, from the ground";
		t.check(ticks >= 0 && ticks <= 8, detail);
		t.note(detail);
	}

	@Test(name = "glide_bind_air", tags = "elytra")
	public void glideBindAir(TestContext t) {
		t.player().give(38, "elytra");
		launch(t, 9000.5, 120, 0.5);
		t.waitTicks(10);
		t.flagsFromHere();
		t.module(TWEAKS).set("glide_bind", "g").set("air_brake", "false");
		t.step("key g click");
		int ticks = ticksToGlide(t, 20);
		t.waitTicks(20);
		boolean still = t.onGame(() -> Minecraft.getInstance().player.isFallFlying());
		t.module(TWEAKS).set("glide_bind", "none");
		String detail = ticks < 0 ? "never glided" : "gliding " + ticks + " ticks after the bind, in the air" + (still ? ", still a second later" : ", stopped after");
		t.check(ticks >= 0 && ticks <= 3 && still, detail);
		t.note(detail);
	}

	/**
	 * Rockets at a wall 45 blocks ahead, east ({@code north} false) or north: the health after the flight, and the gap to
	 * the wall when the brake first turned the look sent (NaN if it never did).
	 */
	private record Hit(float health, double brakeGap, double brakeSpeed) {}

	private static Hit rocketAtWall(TestContext t, boolean brake, double side, boolean north) {
		var a = t.arena();
		int x0 = 10000, z0 = (int) side;
		a.forceload(x0 - 60, z0 - 60, x0 + 60, z0 + 60);
		if (north) a.fill(x0 - 16, 20, z0 - 46, x0 + 16, 100, z0 - 45, "stone");
		else a.fill(x0 + 45, 20, z0 - 16, x0 + 46, 100, z0 + 16, "stone");
		t.player().give(38, "elytra").give(0, "firework_rocket", 16).select(0);
		launch(t, x0 + 0.5, 60, z0 + 0.5);
		float yaw = north ? 180 : -90;
		t.player().look(yaw, 0);
		t.waitTicks(10);
		t.module(TWEAKS).set("air_brake", String.valueOf(brake)).set("no_durability", "false");
		t.hold(Key.JUMP).forTicks(2);
		t.waitTicks(3);
		t.step("use");
		t.waitTicks(15);
		t.step("use");
		// The wall's face along the flight, and how far from it the brake first came on.
		double face = north ? a.z(z0 - 45) : a.x(x0 + 45);
		double[] gap = {Double.NaN, Double.NaN};
		for (int i = 0; i < 60; i++) {
			if (Double.isNaN(gap[0])) {
				double[] at = t.onGame(() -> {
					var p = net.minecraft.client.Minecraft.getInstance().player;
					// What the server was last sent (asking for this tick's look would pin it before the brake asks).
					float sent = dev.myriadclient.api.Myriad.rotations().serverYaw();
					if (Math.abs(net.minecraft.util.Mth.wrapDegrees(sent - p.getYRot())) < 90) return null;
					var b = p.getBoundingBox();
					return new double[] {north ? b.minZ - face : face - b.maxX, p.getDeltaMovement().horizontalDistance()};
				});
				if (at != null) gap = at;
			}
			t.waitTicks(1);
		}
		t.module(TWEAKS).set("air_brake", "false").set("no_durability", "true");
		return new Hit(t.player().health(), gap[0], gap[1]);
	}

	@Test(name = "air_brake", tags = "elytra", timeoutTicks = 600)
	public void airBrake(TestContext t) {
		t.flagsFromHere();
		Hit hit = rocketAtWall(t, true, 0.5, false);
		String detail = String.format(Locale.ROOT, "health %.1f after rocketing at the wall with Air Brake, on %.1f blocks from it at %.2f b/t",
			hit.health(), hit.brakeGap(), hit.brakeSpeed());
		// Only close to the wall (Brake Distance 3, or the next tick at that speed): the flight before is left alone.
		t.check(hit.health() >= 20 && hit.brakeGap() <= Math.max(3, hit.brakeSpeed()) + 0.3, detail);
		t.note(detail);
	}

	@Test(name = "air_brake_north", tags = "elytra", timeoutTicks = 600)
	public void airBrakeNorth(TestContext t) {
		// The same, flying north: the look goes back the way you came, whichever way that is.
		t.flagsFromHere();
		Hit hit = rocketAtWall(t, true, 300.5, true);
		String detail = String.format(Locale.ROOT, "health %.1f after rocketing north at the wall with Air Brake, on %.1f blocks from it at %.2f b/t",
			hit.health(), hit.brakeGap(), hit.brakeSpeed());
		t.check(hit.health() >= 20 && hit.brakeGap() <= Math.max(3, hit.brakeSpeed()) + 0.3, detail);
		t.note(detail);
	}

	@Test(name = "no_air_brake", tags = "elytra", timeoutTicks = 600)
	public void noAirBrake(TestContext t) {
		// The same flight without it: the wall has to hurt, or the test above proves nothing.
		float hp = rocketAtWall(t, false, 100.5, false).health();
		String detail = String.format(Locale.ROOT, "health %.1f after rocketing at the wall without Air Brake", hp);
		t.check(hp < 20, detail);
		t.note(detail);
	}

	// ---- flying yourself with No Durability: the moments drops come in -------------------------------------------

	/** What a run of flights came to: drops on screen, the catch's messages, health and wear. */
	private static final class Watch {
		int drops, notices, airTicks;
		final java.util.List<String> notes = new java.util.ArrayList<>();
		boolean was;
		int before;
		long since;
	}

	/**
	 * Elytra on, a chestplate in the hotbar, rockets, Elytra Tweaks' No Durability for you: a player's setup (not Auto
	 * Armor, which wears the chestplate on the ground, leaving no elytra to take off with).
	 */
	private static Watch flyYourself(TestContext t, boolean rockets) {
		return flyYourself(t, rockets, true);
	}

	private static Watch flyYourself(TestContext t, boolean rockets, boolean noDurability) {
		t.player().give(38, "elytra").give(1, "diamond_chestplate");
		if (rockets) t.player().give(0, "firework_rocket", 64);
		t.player().select(0);
		t.module(TWEAKS).set("no_durability", String.valueOf(noDurability)).set("air_brake", "false").enable();
		Watch w = new Watch();
		w.before = ElytraScenes.elytraDamage(t);
		return w;
	}

	/** Waits {@code ticks}, counting glides that stop in mid-air on screen (each noted with what it was like). */
	private static void watch(TestContext t, Watch w, int ticks) {
		t.waitUntil("flying", () -> {
			var mc = Minecraft.getInstance();
			var p = mc.player;
			boolean gliding = p.isFallFlying(), free = !p.onGround() && !p.isInWater();
			int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
			// Within a couple of blocks of the ground a glide ending is a landing (vanilla's, and the catch leaves it be too).
			if (w.was && !gliding && free && p.getY() - ground > 2) {
				w.drops++;
				w.notes.add(String.format(Locale.ROOT, "drop %.1f above the ground, v.y %.2f, speed %.2f, chest %s", p.getY() - ground, p.getDeltaMovement().y,
					p.getDeltaMovement().length(), p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem()));
			}
			if (free) w.airTicks++;
			w.was = gliding;
			return false;
		}, ticks);
	}

	private static void judge(TestContext t, Watch w, String what) {
		judge(t, w, what, 3, 0);
	}

	private static void judge(TestContext t, Watch w, String what, int maxDamage) {
		judge(t, w, what, maxDamage, 0);
	}

	/** {@code maxDamage}: the elytra wear allowed (No Durability can't swap close to the ground: a short glide wears it a point). */
	private static void judge(TestContext t, Watch w, String what, int maxDamage, int minAirTicks) {
		t.waitTicks(10);
		w.notices = (int) t.chat().stream().filter(l -> l.contains("Glide dropped")).count();
		int damage = ElytraScenes.elytraDamage(t) - w.before;
		float hp = t.player().health();
		String detail = String.format(Locale.ROOT, "%s: %d drops on screen, %d drop notices, %d ticks in the air, health %.1f, elytra damage %d", what, w.drops,
			w.notices, w.airTicks, hp, damage);
		for (String n : w.notes) t.note(n);
		t.check(w.drops == 0 && w.notices == 0 && hp >= 20 && damage <= maxDamage && w.airTicks >= minAirTicks, detail);
		t.note(detail);
	}

	/** A flat stretch far out, 500 blocks long, with a tower of {@code height} at its start (relative to the arena). */
	private static void stretch(TestContext t, int x, int height) {
		// Long and high enough for a whole flight: past its end, natural ground stands in the way.
		var a = t.arena();
		a.forceload(x - 8, -16, x + 510, 16);
		a.fill(x - 6, -2, -12, x + 500, -1, 12, "stone");
		a.fill(x - 6, 0, -12, x + 500, 60, 12, "air");
		if (height > 0) a.fill(x, 0, -1, x + 2, height - 1, 1, "stone");
	}

	@Test(name = "short_glides", tags = "elytra", timeoutTicks = 2400)
	public void shortGlides(TestContext t) {
		shortGlides(t, true);
	}

	/** {@link #shortGlides} with No Durability off. */
	@Test(name = "short_glides_plain", tags = "elytra", timeoutTicks = 2400)
	public void shortGlidesPlain(TestContext t) {
		shortGlides(t, false);
	}

	private void shortGlides(TestContext t, boolean noDurability) {
		// Off a tower twelve blocks up, the elytra opened as you fall (jump), gliding to the ground: five times.
		stretch(t, 11000, 12);
		// There first from above, so the chunks are in before standing on the tower.
		launch(t, 11001.5, 30, 0.5);
		t.waitTicks(20);
		t.flagsFromHere();
		Watch w = flyYourself(t, false, noDurability);
		for (int i = 0; i < 5; i++) {
			t.player().teleport(11001.5, 12, 0.5, -90, 15);
			t.waitUntil("on the tower", () -> {
				var p = Minecraft.getInstance().player;
				return p.onGround() && p.getY() > t.arena().y(11.5);
			}, 60);
			t.note("on the tower: " + t.onGame(() -> {
				var p = Minecraft.getInstance().player;
				return String.format(Locale.ROOT, "%.1f %.1f %.1f gliding %s", p.getX(), p.getY(), p.getZ(), p.isFallFlying());
			}));
			t.waitTicks(10);
			t.hold(Key.FORWARD).forTicksThenContinue(14);
			// Off the edge and falling: the jump that opens the elytra.
			t.waitUntil("falling", () -> {
				var p = Minecraft.getInstance().player;
				return !p.onGround() && p.getDeltaMovement().y < -0.2;
			}, 40);
			t.hold(Key.JUMP).forTicks(2);
			watch(t, w, 100);
			// The rest of the glide, down to the ground.
			t.waitUntil("down and still", () -> {
				var p = Minecraft.getInstance().player;
				return p.onGround() && !p.isFallFlying() && p.getDeltaMovement().horizontalDistance() < 0.05;
			}, 200);
			t.note("landed " + (i + 1) + ": health " + t.player().health());
			if (t.player().health() <= 0 || t.chat().stream().anyMatch(l -> l.contains("fell from"))) break;
		}
		judge(t, w, "5 glides off a 12 block tower", noDurability ? 12 : Integer.MAX_VALUE);
	}

	@Test(name = "rocket_takeoffs", tags = "elytra", timeoutTicks = 4800)
	public void rocketTakeoffs(TestContext t) {
		rocketTakeoffs(t, true);
	}

	/** {@link #rocketTakeoffs} with No Durability off: what the flight does by itself. */
	@Test(name = "rocket_takeoffs_plain", tags = "elytra", timeoutTicks = 4800)
	public void rocketTakeoffsPlain(TestContext t) {
		rocketTakeoffs(t, false);
	}

	private void rocketTakeoffs(TestContext t, boolean noDurability) {
		// From the ground: jump, jump (the glide), a rocket, a climb, level with rockets, a dive and a landing: three times.
		stretch(t, 12000, 0);
		launch(t, 12000.5, 1, 0.5);
		t.waitTicks(20);
		t.flagsFromHere();
		Watch w = flyYourself(t, true, noDurability);
		for (int i = 0; i < 3; i++) {
			t.player().at(12000.5, 0, 0.5, -90, -40);
			t.waitTicks(10);
			// Jump, and the second press near the top of it, as a player opens the elytra.
			t.hold(Key.JUMP).forTicks(1);
			t.waitUntil("near the top of the jump", () -> {
				var p = Minecraft.getInstance().player;
				return !p.onGround() && p.getDeltaMovement().y < 0.15;
			}, 20);
			t.hold(Key.JUMP).forTicks(1);
			// The rocket as soon as the elytra opens, as you'd take off: from a jump's height the glide touches down in about
			// half a second, and a rocket has to reach the server before that (a round trip, under lag).
			t.waitUntil("gliding", () -> Minecraft.getInstance().player.isFallFlying(), 10);
			t.step("use");
			watch(t, w, 30);
			t.player().look(-90, -5);
			t.step("use");
			watch(t, w, 25);
			t.player().look(-90, 35);
			watch(t, w, 30);
			t.player().look(-90, 10);
			watch(t, w, 50);
			// Down on the ground before the next one (a teleport doesn't clear the fall the server counts), a little steeper
			// so it lands inside the stretch.
			t.player().look(-90, 15);
			java.util.List<String> track = new java.util.ArrayList<>();
			int[] tick = {0};
			t.waitUntil("landed", () -> {
				var p = Minecraft.getInstance().player;
				if (tick[0]++ % 20 == 0) track.add(String.format(Locale.ROOT, "x+%.0f y%.0f %s%s", p.getX() - t.arena().x(12000), p.getY() - t.arena().y(0),
					p.isFallFlying() ? "gliding" : "not", p.horizontalCollision ? " against a wall" : ""));
				return p.onGround() && !p.isFallFlying() && p.getDeltaMovement().horizontalDistance() < 0.05;
			}, 600);
			t.note("landing: " + String.join(", ", track.subList(0, Math.min(track.size(), 12))));
		}
		judge(t, w, "3 take-offs with rockets, a climb, a dive and a landing", noDurability ? 20 : Integer.MAX_VALUE, 300);
	}

	@Test(name = "vertical_rockets", tags = "elytra", timeoutTicks = 2400)
	public void verticalRockets(TestContext t) {
		// High up: straight up on rockets, then straight down, four times, then a glide down.
		t.player().give(38, "elytra");
		launch(t, 13000.5, 150, 0.5);
		t.waitTicks(20);
		t.flagsFromHere();
		Watch w = flyYourself(t, true);
		t.player().look(-90, 0);
		t.hold(Key.JUMP).forTicks(2);
		watch(t, w, 6);
		for (int i = 0; i < 4; i++) {
			t.player().look(-90, -85);
			t.step("use");
			watch(t, w, 30);
			t.step("use");
			watch(t, w, 30);
			t.player().look(-90, 80);
			watch(t, w, 40);
			t.player().look(-90, -20);
			watch(t, w, 20);
		}
		judge(t, w, "4 climbs straight up and dives straight down");
	}
}
