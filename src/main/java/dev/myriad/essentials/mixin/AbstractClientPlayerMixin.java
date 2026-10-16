package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.myriad.essentials.modules.movement.ElytraFlight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Recast: the view doesn't widen and narrow with the sprint its bounce holds and lets go of at every touch. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	/** Vanilla's sprint speed modifier ({@code LivingEntity.SPEED_MODIFIER_SPRINTING}): 30% more, on the total. */
	@Unique
	private static final Identifier ESSENTIALS$SPRINTING = Identifier.withDefaultNamespace("sprinting");
	@Unique
	private static final double ESSENTIALS$SPRINT_FACTOR = 1.3;

	@ModifyExpressionValue(method = "getFieldOfViewModifier", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
	private double essentials$steadyView(double speed) {
		var self = (AbstractClientPlayer) (Object) this;
		if (self != Minecraft.getInstance().player || !ElytraFlight.steadyView()) return speed;
		var instance = self.getAttribute(Attributes.MOVEMENT_SPEED);
		return instance != null && instance.hasModifier(ESSENTIALS$SPRINTING) ? speed / ESSENTIALS$SPRINT_FACTOR : speed;
	}
}
