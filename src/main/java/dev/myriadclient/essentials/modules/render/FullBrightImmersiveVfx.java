package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.module.Modules;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Full Bright under ImmersiveVFX, which lights the world in shaders of its own, from light levels, past the lightmap and
 * night vision that Full Bright's modes change. Its lighting takes one brightness from the game (the video setting,
 * never above 1): while Full Bright is on that goes as {@link #FLAG} (compat.immersivevfx.VfxShadowsMixin), and a line
 * added to its lighting as the shader is read (compat.immersivevfx.FallbackResourceManagerMixin) draws everything fully
 * lit then. Either half missing (a new ImmersiveVFX moved things) leaves its lighting as it was.
 */
public final class FullBrightImmersiveVfx {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad Essentials");
	/** The shader file with ImmersiveVFX's lighting. */
	public static final Identifier LIGHTING = Identifier.fromNamespaceAndPath("immersivevfx", "shaders/include/vfx_shadow.glsl");
	/** Where its lighting reads the brightness; the added line goes after it. */
	private static final String BRIGHTNESS_READ = "float bright = ShadowAmbient.w;";
	/** Fully lit, shaded a little by which way the face points, as vanilla's faces are. */
	private static final String FULLY_LIT = "\n    if (bright > 1.5) return clamp(albedo * ((0.75 + normal.y * 0.25) * (1.0 - 0.1 * normal.x * normal.x)), 0.0, 1.0);";
	/** The brightness ImmersiveVFX's lighting gets while Full Bright is on: one it never gets otherwise. */
	public static final float FLAG = 2f;

	private static boolean warned;

	private FullBrightImmersiveVfx() {
	}

	/** Whether Full Bright is on now. */
	public static boolean active() {
		return Modules.active(FullBright.class) != null;
	}

	/** {@code resource} as read from {@code location}: ImmersiveVFX's lighting with the fully lit line added, anything else as it is. */
	public static Resource patched(Identifier location, Resource resource) {
		if (!LIGHTING.equals(location)) return resource;
		return new Resource(resource.source(), () -> patch(resource.open()), resource::metadata);
	}

	private static InputStream patch(InputStream in) throws IOException {
		String source;
		try (in) {
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int at = source.indexOf(BRIGHTNESS_READ);
		if (at < 0) {
			if (!warned) LOG.warn("Full Bright: ImmersiveVFX's lighting has changed, so Full Bright can't brighten it");
			warned = true;
		} else {
			int end = at + BRIGHTNESS_READ.length();
			source = source.substring(0, end) + FULLY_LIT + source.substring(end);
		}
		return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
	}
}
