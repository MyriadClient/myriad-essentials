package dev.myriadclient.essentials.modules.movement;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.GlideEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Categories;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Modules;
import dev.myriadclient.api.util.MathUtil;
import dev.myriadclient.essentials.mixin.FireworkRocketEntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Grim doesn't simulate a rocket's push. While one is attached to you it takes the plain glide it
 * predicts from last tick's movement and widens it, on each axis, by up to 1.7 times your current look plus the one
 * before it (with a little slack for skipped ticks), capped at 1.7 blocks a tick; anything inside passes. Vanilla's push
 * only ever approaches 1.7 blocks a tick along your look, and takes a while to get there. With Rocket Boost, every
 * tick a rocket is attached you move as far along your look as that window allows (a little inside it): full speed at
 * once, and since the cap is per axis, faster when you fly diagonally. Looking level holds your height. Every
 * movement packet carries your rotation meanwhile, so the look before is exactly the one sent last tick. Applied by
 * this addon's LivingEntityMixin.
 * <p>
 */
public class RocketBoost extends Module {
	/** Grim's allowance for a rocket's push on each axis, blocks a tick (scale and cap alike), less a margin. */
	private static final double ROCKET_PUSH = 1.68;
	/** Slack Grim gives each aim's axes for a skipped tick. */
	private static final double LOOK_SLACK = 0.05;
	/** Ticks of vanilla flight after a setback while Grim catches up. */
	private static final int SETTLE_TICKS = 5;
	/** A target speed beyond any rocket's, so the clamp alone decides how fast. */
	private static final double REACH = 10;

	/** The player's position a tick ago while gliding: the movement Grim's prediction starts from. */
	private Vec3 prevTickPos;
	private volatile boolean setBack;
	private int settleTicks;

	public RocketBoost() {
		super(Categories.MOVEMENT, "Rocket Boost", "While a rocket pushes you, fly as fast along your look as Grim allows: full speed at once, faster on diagonals.");
	}

	@Override
	protected void onEnable() {
		prevTickPos = null;
		settleTicks = 0;
	}

	/** Before this tick's actions: every movement packet carries a rotation while a rocket pushes, so Grim's look before is known. */
	@Subscribe(priority = Priority.BEFORE_ACTIONS)
	private void onTickStart(TickEvent.Pre e) {
		if (!inGame()) return;
		if (settleTicks > 0) settleTicks--;
		LocalPlayer p = mc.player;
		if (p.isFallFlying() && rocketAttached(p)) Myriad.rotations().sendRotationThisTick();
	}

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onReceive(PacketEvent.Receive e) {
		setBack = true;
	}

	/** The boost, after Elytra Fly's Control (a glide it already flies is left alone). */
	@Subscribe(priority = Priority.LOW)
	private void onGlide(GlideEvent e) {
		if (e.isChanged()) return;
		Vec3 boosted = glideMovement(e.movement());
		if (boosted != e.movement()) e.setVelocity(boosted);
	}

	/**
	 * The movement the local player's glide should make this tick, given vanilla's: unchanged unless this is on,
	 * you're gliding with a rocket attached, and no module is turning you server-side (the window is built from the
	 * rotation the server gets, so it has to be yours).
	 */
	private static Vec3 glideMovement(Vec3 vanilla) {
		RocketBoost m = Modules.active(RocketBoost.class);
		if (m == null) return vanilla;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !p.isFallFlying()) {
			m.prevTickPos = null;
			return vanilla;
		}
		Vec3 here = p.position(), there = m.prevTickPos;
		m.prevTickPos = here;
		if (m.setBack) {
			m.setBack = false;
			m.settleTicks = SETTLE_TICKS;
		}
		if (there == null || m.settleTicks > 0 || p.hurtTime > 0 || Myriad.rotations().isRotating() || !rocketAttached(p)) return vanilla;
		// Grim works from the movement it saw last tick, not the client's velocity.
		Vec3 carried = here.subtract(there);
		if (carried.lengthSqr() > 40 * 40) return vanilla;
		// Unless something cut or slowed that movement: Grim carries on from what was left of it, which isn't known here.
		if (p.horizontalCollision || p.verticalCollision || heldBack(p, there)) return vanilla;
		Vec3 aim = MathUtil.direction(p.getYRot(), p.getXRot());
		Vec3 prevAim = MathUtil.direction(Myriad.rotations().serverYaw(), Myriad.rotations().serverPitch());
		Vec3 coast = vanillaGlide(p, carried, aim, p.getXRot());
		// Reach for far past any speed along the aim, then pull back into what Grim allows on each axis.
		Vec3 far = aim.scale(REACH);
		Vec3 pushed = new Vec3(
			Math.clamp(far.x, allowed(aim.x, prevAim.x, carried.x, coast.x, false), allowed(aim.x, prevAim.x, carried.x, coast.x, true)),
			Math.clamp(far.y, allowed(aim.y, prevAim.y, carried.y, coast.y, false), allowed(aim.y, prevAim.y, carried.y, coast.y, true)),
			Math.clamp(far.z, allowed(aim.z, prevAim.z, carried.z, coast.z, false), allowed(aim.z, prevAim.z, carried.z, coast.z, true)));
		// Vanilla's push is always allowed too; only take over where this goes further along the aim.
		return pushed.dot(aim) > vanilla.dot(aim) ? pushed : vanilla;
	}

	/**
	 * One edge of what Grim accepts on one axis: the rocket-free glide ({@code coast}), stretched by however far the
	 * rocket's reach (from this tick's and last tick's aim, each given a little slack) goes past last tick's movement.
	 */
	private static double allowed(double aim, double prevAim, double carried, double coast, boolean upper) {
		double reach = upper ? Math.max(LOOK_SLACK, aim) + Math.max(LOOK_SLACK, prevAim) : Math.min(-LOOK_SLACK, aim) + Math.min(-LOOK_SLACK, prevAim);
		double rocket = Math.clamp(reach * ROCKET_PUSH, -ROCKET_PUSH, ROCKET_PUSH);
		double stretch = rocket - carried;
		return coast + (upper ? Math.max(0, stretch) : Math.min(0, stretch));
	}

	/** Vanilla's elytra step (Grim predicts the same) from {@code motion} with {@code aim}, before any rocket, drag included. */
	private static Vec3 vanillaGlide(LocalPlayer p, Vec3 motion, Vec3 aim, float pitchDegrees) {
		double g = p.getAttributeValue(Attributes.GRAVITY);
		if (motion.y <= 0 && p.hasEffect(MobEffects.SLOW_FALLING)) g = Math.min(g, 0.01);
		float rad = pitchDegrees * Mth.DEG_TO_RAD;
		double aimFlat = aim.horizontalDistance();
		double flatSpeed = motion.horizontalDistance();
		double cos = Math.cos(rad);
		double liftFactor = cos * cos * Math.min(1, aim.length() / 0.4);
		Vec3 next = motion.add(0, g * (liftFactor * 0.75 - 1), 0);
		if (aimFlat > 0) {
			double dirX = aim.x / aimFlat, dirZ = aim.z / aimFlat;
			if (next.y < 0) {
				// Falling converts into forward speed.
				double k = -0.1 * next.y * liftFactor;
				next = next.add(dirX * k, k, dirZ * k);
			}
			if (rad < 0) {
				// Pulling up trades forward speed for height.
				double k = -0.04 * flatSpeed * Mth.sin(rad);
				next = next.add(-dirX * k, 3.2 * k, -dirZ * k);
			}
			next = next.add((dirX * flatSpeed - next.x) * 0.1, 0, (dirZ * flatSpeed - next.z) * 0.1);
		}
		return next.multiply(0.99f, 0.98f, 0.99f);
	}

	/**
	 * Whether a block along last tick's path held the movement back: a liquid, one that slows whatever is on or in it
	 * (soul sand, honey: from up to half a block below, as vanilla looks), or one you get stuck in (cobwebs, sweet berry
	 * bushes, powder snow).
	 */
	private static boolean heldBack(LocalPlayer p, Vec3 from) {
		AABB box = p.getBoundingBox().expandTowards(from.subtract(p.position())).expandTowards(0, -0.5, 0);
		var level = p.level();
		var pos = new BlockPos.MutableBlockPos();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
			for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
				for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					if (state.isAir()) continue;
					Block block = state.getBlock();
					if (!state.getFluidState().isEmpty() || block.getSpeedFactor() != 1 || block instanceof WebBlock
						|| block instanceof SweetBerryBushBlock || block instanceof PowderSnowBlock) return true;
				}
			}
		}
		return false;
	}

	/** Whether a rocket is boosting {@code p}, as the server told us (the same moment Grim learns it). */
	private static boolean rocketAttached(LocalPlayer p) {
		for (FireworkRocketEntity rocket : p.level().getEntitiesOfClass(FireworkRocketEntity.class, p.getBoundingBox().inflate(4))) {
			if (rocket.isRemoved()) continue;
			var target = rocket.getEntityData().get(FireworkRocketEntityAccessor.essentials$attachedTarget());
			if (target.isPresent() && target.getAsInt() == p.getId()) return true;
		}
		return false;
	}
}
