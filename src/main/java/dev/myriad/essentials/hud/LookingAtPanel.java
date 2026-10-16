package dev.myriad.essentials.hud;

import dev.myriad.api.Myriad;
import dev.myriad.api.render.Animated;
import dev.myriad.api.render.Canvas;
import dev.myriad.api.render.EntityGroups;
import dev.myriad.api.render.FontFamily;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.SettingColor;
import dev.myriad.api.ui.Rect;
import dev.myriad.api.ui.ThemeSettings;
import dev.myriad.api.ui.hud.HudPanel;
import dev.myriad.api.ui.hud.ItemHud;
import dev.myriad.api.util.Entities;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * What the crosshair is on, in a card in the theme's colours: the block or entity with its icon and name, and what's
 * worth knowing about it. Blocks: the tool that harvests it (green if what you hold does, red if it doesn't and the
 * block needs one), how long it takes to break with what you hold, a crop's growth and redstone power. Entities:
 * health, armour, a player's ping, a horse's speed. Fades out when you look at nothing.
 */
public final class LookingAtPanel extends HudPanel {
	private final BoolSetting blockDetails = sgGeneral.bool("Block Details").description("Tool, break time, growth and power.").defaultValue(true).build();
	private final BoolSetting entityDetails = sgGeneral.bool("Entity Details").description("Health, armour, ping, a horse's speed.").defaultValue(true).build();
	private final BoolSetting background = sgGeneral.bool("Background").description("Draw the card behind the text.").defaultValue(true).build();

	private static final TagKey<?>[] TOOL_TAGS = {BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.MINEABLE_WITH_AXE, BlockTags.MINEABLE_WITH_SHOVEL, BlockTags.MINEABLE_WITH_HOE};
	private static final String[] TOOL_NAMES = {"Pickaxe", "Axe", "Shovel", "Hoe"};
	/** Blocks per second for each point of a horse's movement speed attribute. */
	private static final double HORSE_SPEED_TO_BPS = 42.16;

	/** One line: a label and its value, the value in its own colour. */
	private record Line(String label, String value, int color) {
	}

	/** What's shown: an icon, the name in its colour, and the lines under it. */
	private record Info(ItemStack icon, String title, int titleColor, List<Line> lines) {
	}

	private final Animated shown = new Animated(0);
	/** What was last looked at, kept while the card fades out. */
	private Info last;

	public LookingAtPanel() {
		super("Looking At", "");
	}

	private ThemeSettings theme() {
		return Myriad.ui().theme();
	}

	private Info current() {
		if (preview()) {
			List<Line> lines = new ArrayList<>();
			lines.add(new Line("Tool", "Shovel", theme().green.argb()));
			lines.add(new Line("Breaks", "0.75 s", theme().text.argb()));
			return new Info(Items.GRASS_BLOCK.getDefaultInstance(), "Grass Block", theme().text.argb(), lines);
		}
		if (mc.player == null || mc.level == null) return null;
		HitResult hit = mc.hitResult;
		if (hit instanceof EntityHitResult e) return entity(e.getEntity());
		if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return block(b.getBlockPos());
		return null;
	}

	private Info block(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) return null;
		Block block = state.getBlock();
		ItemStack icon = state.getCloneItemStack(mc.level, pos, false);
		List<Line> lines = new ArrayList<>();
		if (blockDetails.get()) {
			ThemeSettings t = theme();
			for (int i = 0; i < TOOL_TAGS.length; i++) {
				@SuppressWarnings("unchecked")
				TagKey<Block> tag = (TagKey<Block>) TOOL_TAGS[i];
				if (!state.is(tag)) continue;
				boolean needed = state.requiresCorrectToolForDrops();
				boolean harvests = !needed || mc.player.hasCorrectToolForDrops(state);
				lines.add(new Line("Tool", TOOL_NAMES[i], harvests ? (needed ? t.green.argb() : t.text.argb()) : t.red.argb()));
				break;
			}
			if (mc.player.isCreative()) {
				lines.add(new Line("Breaks", "Instantly", t.green.argb()));
			} else if (state.getDestroySpeed(mc.level, pos) < 0) {
				lines.add(new Line("Breaks", "Never", t.red.argb()));
			} else {
				float perTick = state.getDestroyProgress(mc.player, mc.level, pos);
				if (perTick >= 1) lines.add(new Line("Breaks", "Instantly", t.green.argb()));
				else if (perTick > 0) lines.add(new Line("Breaks", String.format("%.2f s", Math.ceil(1 / perTick) / 20), t.text.argb()));
			}
			if (grows(block)) {
				IntegerProperty age = intProperty(state, "age");
				if (age != null) {
					int value = state.getValue(age), max = age.getPossibleValues().getLast();
					lines.add(value >= max ? new Line("Growth", "Mature", t.green.argb()) : new Line("Growth", (100 * value / max) + "%", t.text.argb()));
				}
			}
			if (state.hasProperty(BlockStateProperties.POWER)) lines.add(new Line("Power", String.valueOf(state.getValue(BlockStateProperties.POWER)), t.red.argb()));
		}
		return new Info(icon, block.getName().getString(), theme().text.argb(), lines);
	}

	/** Blocks whose age is how far they've grown (a sugar cane's or a cactus's only times the next block). */
	private static boolean grows(Block block) {
		return block instanceof CropBlock || block instanceof StemBlock || block instanceof CocoaBlock || block instanceof NetherWartBlock
			|| block instanceof SweetBerryBushBlock || block instanceof PitcherCropBlock;
	}

	private static IntegerProperty intProperty(BlockState state, String name) {
		for (Property<?> p : state.getProperties()) if (p instanceof IntegerProperty ip && p.getName().equals(name)) return ip;
		return null;
	}

	private Info entity(Entity entity) {
		ThemeSettings t = theme();
		List<Line> lines = new ArrayList<>();
		String title = entity.getDisplayName().getString();
		// The spawn egg, or what picking the entity gives (a boat, a minecart, an end crystal).
		ItemStack icon = SpawnEggItem.byId(entity.getType()).map(h -> new ItemStack(h.value())).orElseGet(entity::getPickResult);
		if (icon == null) icon = ItemStack.EMPTY;
		if (entity instanceof LivingEntity living && living.isBaby()) title += " (Baby)";
		if (entityDetails.get() && entity instanceof LivingEntity living) {
			float hp = living.getHealth(), max = living.getMaxHealth(), absorption = living.getAbsorptionAmount();
			String health = String.format("%.1f / %.0f", hp, max) + (absorption > 0 ? String.format(" +%.0f", absorption) : "");
			lines.add(new Line("Health", health, ItemHud.durabilityColor(max > 0 ? hp / max : 0)));
			if (living.getArmorValue() > 0) lines.add(new Line("Armor", String.valueOf(living.getArmorValue()), t.text.argb()));
			if (entity instanceof Player player && player != mc.player) {
				int ping = Entities.ping(player);
				if (ping > 0) lines.add(new Line("Ping", ping + " ms", t.text.argb()));
			}
			if (entity instanceof AbstractHorse horse) {
				double speed = horse.getAttributeValue(Attributes.MOVEMENT_SPEED) * HORSE_SPEED_TO_BPS;
				lines.add(new Line("Speed", String.format("%.1f b/s", speed), t.text.argb()));
			}
		}
		EntityGroups.Group group = EntityGroups.of(entity);
		int color = SettingColor.role(group != null ? group.color : SettingColor.Mode.TEXT).argb();
		return new Info(icon, title, color, lines);
	}

	// ---- layout -----------------------------------------------------------------------------------------------------

	private float pad() {
		return 5 * scale.getFloat();
	}

	private float iconSize() {
		return 16 * scale.getFloat();
	}

	private float lineSize(Canvas c) {
		return fontSize(c) * 0.95f;
	}

	private float textWidth(Canvas c, Info info) {
		float w = c.textWidth(FontFamily.SANS_BOLD, fontSize(c), info.title());
		for (Line l : info.lines()) w = Math.max(w, c.textWidth(FontFamily.SANS, lineSize(c), l.label() + "  " + l.value()));
		return w;
	}

	private float textHeight(Canvas c, Info info) {
		return c.textHeight(FontFamily.SANS_BOLD, fontSize(c)) + info.lines().size() * (c.textHeight(FontFamily.SANS, lineSize(c)) + 1);
	}

	private Rect size(Canvas c, Info info) {
		if (info == null) return new Rect(0, 0, 0, 0);
		boolean hasIcon = !info.icon().isEmpty();
		float w = pad() * 2 + (hasIcon ? iconSize() + pad() : 0) + textWidth(c, info);
		float h = pad() * 2 + Math.max(hasIcon ? iconSize() : 0, textHeight(c, info));
		return new Rect(0, 0, w, h);
	}

	@Override
	public Rect preferredSize(Canvas c) {
		Info info = current();
		return size(c, info != null ? info : last);
	}

	@Override
	public void render(Canvas c, float w, float h, float mx, float my) {
		ThemeSettings t = theme();
		Info info = current();
		shown.animateTo(info != null ? 1 : 0, 150, t.bezier());
		if (info != null) last = info;
		float alpha = shown.get();
		if (last == null || alpha <= 0.01f) return;
		Info show = last;
		Rect r = size(c, show);
		float radius = t.rounding.get(), pad = pad(), icon = iconSize();
		// Grow from the side the element is anchored to.
		float x = alignRight() ? w - r.w() : 0;
		c.push();
		c.alpha(alpha);
		if (background.get()) {
			if (t.blur.get()) c.backdrop(x, 0, r.w(), r.h(), radius, 1);
			c.roundRect(x, 0, r.w(), r.h(), radius, t.windowBackground.argb());
			if (t.borderSize.get() > 0) c.gradientOutline(x, 0, r.w(), r.h(), radius, t.borderSize.get(), t.activeBorderFrom.argb(), t.activeBorderTo.argb(), t.borderAngle.getFloat());
		}
		float tx = x + pad;
		if (!show.icon().isEmpty()) {
			c.item(show.icon(), tx, (r.h() - icon) / 2, icon);
			tx += icon + pad;
		}
		float ty = (r.h() - textHeight(c, show)) / 2;
		c.text(FontFamily.SANS_BOLD, fontSize(c), show.title(), tx, ty, show.titleColor());
		ty += c.textHeight(FontFamily.SANS_BOLD, fontSize(c));
		float ls = lineSize(c), lh = c.textHeight(FontFamily.SANS, ls) + 1;
		for (Line l : show.lines()) {
			float lx = tx + c.text(FontFamily.SANS, ls, l.label() + "  ", tx, ty + 1, t.textDim.argb());
			c.text(FontFamily.SANS, ls, l.value(), lx, ty + 1, l.color());
			ty += lh;
		}
		c.pop();
	}
}
