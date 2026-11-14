package dev.myriadclient.essentials.mixin;

import dev.myriadclient.api.module.Modules;
import dev.myriadclient.essentials.modules.render.TimeOfDay;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Time of Day: everything the client draws by the time of day (the sky, sun and moon, daylight, the clock item) reads
 * the overworld clock through here. The End's clock is left alone.
 */
@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class ClientClockInstanceMixin {
	@Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true)
	private void essentials$timeOfDay(CallbackInfoReturnable<Long> cir) {
		TimeOfDay t = Modules.active(TimeOfDay.class);
		if (t != null && (Object) this == TimeOfDay.overworldClock) cir.setReturnValue(t.lock(cir.getReturnValueJ()));
	}
}
