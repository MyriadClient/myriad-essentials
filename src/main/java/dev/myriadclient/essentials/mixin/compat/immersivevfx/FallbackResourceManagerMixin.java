package dev.myriadclient.essentials.mixin.compat.immersivevfx;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriadclient.essentials.modules.render.FullBrightImmersiveVfx;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Full Bright under ImmersiveVFX: its lighting shader read with the line that draws everything fully lit while Full
 * Bright is on (see {@link FullBrightImmersiveVfx}). Both ways a shader is read: one by name, and all of a folder
 * (Minecraft's shader loading).
 */
@Mixin(FallbackResourceManager.class)
abstract class FallbackResourceManagerMixin {
	@ModifyReturnValue(method = "getResource", at = @At("RETURN"))
	private Optional<Resource> essentials$fullBright(Optional<Resource> found, Identifier location) {
		return found.map(r -> FullBrightImmersiveVfx.patched(location, r));
	}

	@ModifyReturnValue(method = "listResources", at = @At("RETURN"))
	private Map<Identifier, Resource> essentials$fullBright(Map<Identifier, Resource> found, String directory, ResourceManager.Selector selector) {
		if (!found.containsKey(FullBrightImmersiveVfx.LIGHTING)) return found;
		Map<Identifier, Resource> patched = new LinkedHashMap<>(found);
		patched.replaceAll(FullBrightImmersiveVfx::patched);
		return patched;
	}
}
