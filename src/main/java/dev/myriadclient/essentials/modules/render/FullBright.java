package dev.myriadclient.essentials.modules.render;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.setting.EnumSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Lights everything up. Gamma pushes the lightmap past the brightest video setting; Night Vision gives you a
 * client-side night vision effect instead, with vanilla's night vision look. Shader packs light the world themselves
 * and ignore the lightmap, so in either mode they're told you have night vision (see this addon's GameRendererMixin).
 */
public class FullBright extends Module {
	public enum Mode {
		GAMMA, NIGHT_VISION
	}

	/** Far past vanilla's 1.0 ("Bright"), so even unlit caves read clearly. */
	public static final float GAMMA = 15f;

	private final EnumSetting<Mode> mode = sgGeneral.enumSetting("Mode", Mode.GAMMA)
		.description("Gamma brightens the lightmap; Night Vision gives a client-side night vision effect. Both work with shaders.").build();

	/** The night vision effect this module gave you, so only that one is ever removed (never a real one). */
	private MobEffectInstance added;

	public FullBright() {
		super(Tags.RENDER, "Full Bright", "See in the dark.");
	}

	/** Read by this addon's lightmap mixin. */
	public boolean gamma() {
		return mode.get() == Mode.GAMMA;
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (!inGame()) {
			added = null;
			return;
		}
		if (mode.get() == Mode.NIGHT_VISION) {
			if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
				added = new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false);
				mc.player.addEffect(added);
			}
		} else {
			removeEffect();
		}
	}

	@Override
	protected void onDisable() {
		removeEffect();
	}

	private void removeEffect() {
		if (added == null || !inGame()) return;
		if (mc.player.getEffect(MobEffects.NIGHT_VISION) == added) mc.player.removeEffect(MobEffects.NIGHT_VISION);
		added = null;
	}
}
