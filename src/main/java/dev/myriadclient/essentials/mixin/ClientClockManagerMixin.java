package dev.myriadclient.essentials.mixin;

import dev.myriadclient.essentials.modules.render.TimeOfDay;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Time of Day: remembers which clock is the overworld's (see ClientClockInstanceMixin). */
@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {
	@Inject(method = "getInstance", at = @At("RETURN"))
	private void essentials$overworldClock(Holder<WorldClock> definition, CallbackInfoReturnable<ClientClockManager.ClientClockInstance> cir) {
		if (definition.is(WorldClocks.OVERWORLD)) TimeOfDay.overworldClock = cir.getReturnValue();
	}
}
