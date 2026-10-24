package dev.myriadclient.essentials.modules.render;

import com.google.gson.JsonPrimitive;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.EntityRenderEvent;
import dev.myriadclient.api.event.events.PreviewEvent;
import dev.myriadclient.api.event.events.Render2DEvent;
import dev.myriadclient.api.event.events.RenderEvent;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.EntityGroups.Group;
import dev.myriadclient.api.render.EntityGroups;
import dev.myriadclient.api.render.ModelShapes;
import dev.myriadclient.api.render.Projection;
import dev.myriadclient.api.render.RenderMode;
import dev.myriadclient.api.render.RenderSettings;
import dev.myriadclient.api.render.RenderStates;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.ui.widget.Preview;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Entities;
import dev.myriadclient.api.setting.SavedSettings;
import dev.myriadclient.api.setting.SettingGroup;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Highlights entities through walls, by group (players, friends, monsters, animals, crystals, items, pearls, vehicles,
 * …) or by entity type, each group in its own theme colour. Drawn in the render mode (the Render window): in Shader
 * mode an outline around each entity's exact shape (armour and held items included), with its glow, fill and gradient;
 * in Box mode one of three styles:
 * <ul>
 * <li>3D: the hitbox.</li>
 * <li>2D: a rectangle on screen, optionally with a health bar.</li>
 * <li>Model: a wireframe and fill of the entity's model; anything without a model falls back to its box.</li>
 * </ul>
 */
public class ESP extends Module {
	/** How entities are drawn in Box mode. */
	public enum BoxStyle {
		THREE_D("3D"), TWO_D("2D"), MODEL("Model");

		private final String label;

		BoxStyle(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static final long FADE_MS = 200;

	private final DoubleSetting range = sgGeneral.decimal("Range").description("0 = no limit.").defaultValue(0).range(0, 256).decimals(0).build();
	private final EntityGroups targets = new EntityGroups(settings, Set.of(Group.PLAYERS, Group.FRIENDS, Group.MONSTERS, Group.CRYSTALS, Group.PEARLS));

	private final SettingGroup sgRender = settings.group("Render");
	private final EnumSetting<BoxStyle> boxStyle = sgRender.oneOf("Box Style", BoxStyle.THREE_D)
		.description("In Box mode: 3D hitboxes, 2D rectangles on screen, or the model's wireframe.")
		.visible(() -> RenderMode.current() == RenderMode.BOX).build();
	private final BoolSetting fade = sgRender.bool("Fade In").description("Fade entities in as they appear.").defaultValue(true).build();
	private final BoolSetting healthBar = sgRender.bool("Health Bar").description("A health bar beside each rectangle.")
		.visible(() -> style() == BoxStyle.TWO_D).build();
	private final RenderSettings look = RenderSettings.builder(sgRender).build();

	/** When each entity id was first drawn, for the fade-in; ids not drawn this frame are dropped. */
	private final Int2LongOpenHashMap appeared = new Int2LongOpenHashMap();
	private final IntOpenHashSet seen = new IntOpenHashSet();

	public ESP() {
		super(Tags.RENDER, "ESP", "Highlights players, mobs, crystals and more through walls.");
	}

	/** A zombie, drawn as a monster is (see {@link #onPreview}). */
	@Override
	public Widget preview() {
		return Preview.entity(EntityTypes.ZOMBIE);
	}

	/**
	 * 2: how it's drawn (the mode, fade, health bar and the box and outline styles) moved into one Render group. 3: the
	 * render mode is everyone's (the Render window); Mode became the Box Style, and the box and outline options one look.
	 */
	@Override
	public int settingsVersion() {
		return 3;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			for (String s : new String[]{"Mode", "Fade In", "Health Bar", "Shape", "Fill Opacity", "Line Width", "Through Walls"}) saved.move("General", s, "Render", s);
			saved.renameGroup("Outline", "Render");
		}
		if (fromVersion == 2) {
			saved.map("Render", "Mode", v -> new JsonPrimitive(switch (v.getAsString()) {
				case "BOX_2D" -> BoxStyle.TWO_D.name();
				case "MODEL" -> BoxStyle.MODEL.name();
				default -> BoxStyle.THREE_D.name();
			}));
			saved.rename("Render", "Mode", "Box Style");
			RenderSettings.migrate(saved, "Render", null);
		}
	}

	@Override
	protected void onEnable() {
		appeared.clear();
	}

	/** The Box mode style, or null in Shader mode. */
	private @Nullable BoxStyle style() {
		return RenderMode.current() == RenderMode.BOX ? boxStyle.get() : null;
	}

	/** The colour to draw {@code e} in, or 0 to skip it. */
	private int colorFor(Entity e) {
		if (range.get() > 0 && e != mc.player && mc.player.distanceToSqr(e) > range.get() * range.get()) return 0;
		return targets.color(e);
	}

	private float fadeFor(Entity e, long now) {
		seen.add(e.getId());
		if (!fade.get()) return 1;
		long since = appeared.get(e.getId());
		if (since == 0) appeared.put(e.getId(), since = now);
		return Mth.clamp((now - since) / (float) FADE_MS, 0, 1);
	}

	/** The fade-in of an entity already seen this frame (see {@link #fadeFor}). */
	private float fadeOf(Entity e) {
		if (!fade.get()) return 1;
		long since = appeared.get(e.getId());
		return since == 0 ? 0 : Mth.clamp((System.currentTimeMillis() - since) / (float) FADE_MS, 0, 1);
	}

	/** Every entity to show, in the render mode; 2D rectangles and models are drawn as the frame goes on. */
	@Subscribe(inGame = true)
	private void onRender(RenderEvent e) {
		BoxStyle style = style();
		long now = System.currentTimeMillis();
		for (Entity entity : mc.level.entitiesForRendering()) {
			int c = colorFor(entity);
			if (c == 0) continue;
			float f = fadeFor(entity, now);
			if (style == BoxStyle.TWO_D || style == BoxStyle.MODEL && entity instanceof LivingEntity) continue;
			e.entity(entity, look, ColorUtil.withAlpha(c, (int) (ColorUtil.alpha(c) * f)));
		}
		appeared.keySet().retainAll(seen);
		seen.clear();
	}

	/** A preview's entity, as it would be in the world. */
	@Subscribe(inGame = true)
	private void onPreview(PreviewEvent e) {
		if (e.entity() == null) return;
		int c = colorFor(e.entity());
		if (c != 0) e.render(look, c);
	}

	/** Box mode's Model style: as an entity's model is submitted, its wireframe and fill go with it. */
	@Subscribe(inGame = true)
	@SuppressWarnings("unchecked")
	private void onModel(EntityRenderEvent.Model event) {
		if (style() != BoxStyle.MODEL) return;
		LivingEntityRenderState state = event.state();
		Entity e = RenderStates.entity(state);
		if (e == null) return;
		int c = colorFor(e);
		if (c == 0) return;
		float f = fadeOf(e);
		Model<LivingEntityRenderState> m = (Model<LivingEntityRenderState>) event.model();
		int faded = ColorUtil.withAlpha(c, (int) (ColorUtil.alpha(c) * f));
		int fill = look.boxFill(faded), line = look.boxLine(faded);
		if (fill != 0) ModelShapes.fill(event.submits(), event.pose(), m, state, fill, look.throughWalls());
		if (line != 0) ModelShapes.wireframe(event.submits(), event.pose(), m, state, line, look.lineWidth.getFloat(), look.throughWalls());
	}

	/** Box mode's 2D style: a rectangle around each entity on screen. */
	@Subscribe(inGame = true)
	private void onRender2D(Render2DEvent e) {
		if (style() != BoxStyle.TWO_D) return;
		Canvas c = e.canvas();
		for (Entity entity : mc.level.entitiesForRendering()) {
			int color = colorFor(entity);
			if (color == 0) continue;
			float[] r = project(Entities.lerpedBox(entity, e.tickDelta()));
			if (r == null) continue;
			drawBox2D(c, entity, r, color, fadeOf(entity));
		}
	}

	private void drawBox2D(Canvas c, Entity entity, float[] r, int color, float f) {
		float x = r[0], y = r[1], w = r[2] - r[0], h = r[3] - r[1];
		float width = look.lineWidth.getFloat();
		int faded = ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * f));
		int fill = look.boxFill(faded), line = look.boxLine(faded);
		if (fill != 0) c.rect(x, y, w, h, fill);
		if (line != 0) {
			c.outline(x - 0.5f, y - 0.5f, w + 1, h + 1, 0, width + 1, ColorUtil.withAlpha(0xFF000000, (int) (ColorUtil.alpha(color) * f / 2)));
			c.outline(x, y, w, h, 0, width, line);
		}
		if (healthBar.get() && entity instanceof LivingEntity living) {
			float hp = Mth.clamp(living.getHealth() / living.getMaxHealth(), 0, 1);
			float bx = x - 3.5f;
			c.rect(bx - 0.5f, y - 0.5f, 2, h + 1, 0x99000000);
			c.rect(bx, y + h * (1 - hp), 1, h * hp, ColorUtil.lerp(0xFFFF5555, 0xFF55FF55, hp));
		}
	}

	/** Screen rect [left, top, right, bottom] around a world box, or null when it's off screen. */
	private static float[] project(AABB b) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		var window = mc.getWindow();
		float sw = window.getGuiScaledWidth(), sh = window.getGuiScaledHeight();
		boolean any = false;
		for (int i = 0; i < 8; i++) {
			Vec3 s = Projection.toScreen(new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ));
			// Corners right by the near plane project far off screen; skip them.
			if (s == null || s.x < -sw * 2 || s.x > sw * 3 || s.y < -sh * 2 || s.y > sh * 3) continue;
			minX = Math.min(minX, (float) s.x);
			minY = Math.min(minY, (float) s.y);
			maxX = Math.max(maxX, (float) s.x);
			maxY = Math.max(maxY, (float) s.y);
			any = true;
		}
		if (!any) return null;
		minX = Math.max(0, minX);
		minY = Math.max(0, minY);
		maxX = Math.min(sw, maxX);
		maxY = Math.min(sh, maxY);
		return maxX <= minX || maxY <= minY ? null : new float[]{minX, minY, maxX, maxY};
	}
}
