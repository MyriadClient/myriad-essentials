package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.essentials.mixin.MultiPlayerGameModeAccessor;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.EntityGroups;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.hud.HudPanel;
import dev.myriadclient.api.ui.hud.ItemHud;
import dev.myriadclient.api.util.Entities;
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
 * What the crosshair is on, in a wide card in the theme's colours: the block or entity with its icon and name, and on
 * one row beside each other what's worth knowing about it. Blocks: the tool that harvests it (green if what you hold
 * does, red if it doesn't and the block needs one), how long it takes to break with what you hold, a crop's growth and
 * redstone power. Entities: health, armour, a player's ping, a horse's speed. While the block is being broken (by your
 * hand or a module mining it), a bar along the bottom fills with the progress, its percentage shows by the name, and
 * the border's gradient turns. Fades out when you look at nothing.
 */
public final class LookingAtPanel extends HudPanel {
	private final BoolSetting blockDetails = sgGeneral.bool("Block Details").description("Tool, break time, growth and power.").defaultValue(true).build();
	private final BoolSetting entityDetails = sgGeneral.bool("Entity Details").description("Health, armour, ping, a horse's speed.").defaultValue(true).build();
	private final BoolSetting background = sgGeneral.bool("Background").description("Draw the card behind the text.").defaultValue(true).build();
	private final BoolSetting breakBar = sgGeneral.bool("Break Progress").description("A bar filling as the block you look at is broken, by you or a module.")
		.defaultValue(true).build();

	private static final TagKey<?>[] TOOL_TAGS = {BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.MINEABLE_WITH_AXE, BlockTags.MINEABLE_WITH_SHOVEL, BlockTags.MINEABLE_WITH_HOE};
	private static final String[] TOOL_NAMES = {"Pickaxe", "Axe", "Shovel", "Hoe"};
	/** Blocks per second for each point of a horse's movement speed attribute. */
	private static final double HORSE_SPEED_TO_BPS = 42.16;

	/** One line: a label and its value, the value in its own colour. */
	private record Line(String label, String value, int color) {
	}

	/** What's shown: an icon, the name in its colour, the details, and the block's position (null for an entity). */
	private record Info(ItemStack icon, String title, int titleColor, List<Line> lines, BlockPos pos) {
	}

	/** Between details on their row. */
	private static final String GAP = "   ";
	/** Seconds the border's gradient takes to turn once while a block is being broken. */
	private static final float BORDER_TURN_SECONDS = 2.5f;

	private final Animated shown = new Animated(0);
	/** The break bar's fill, eased towards the progress so it doesn't step tick by tick. */
	private final Animated progress = new Animated(0);
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
			return new Info(Items.GRASS_BLOCK.getDefaultInstance(), "Grass Block", theme().text.argb(), lines, BlockPos.ZERO);
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
		return new Info(icon, block.getName().getString(), theme().text.argb(), lines, pos.immutable());
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
		return new Info(icon, title, color, lines, null);
	}

	/** How far the block in {@code info} has got being broken, 0 to 1: by your hand, or a module through the breaking service. */
	private float breakProgress(Info info) {
		if (info.pos() == null || !breakBar.get()) return 0;
		if (preview()) return 0.6f;
		float service = Myriad.breaking().progress(info.pos());
		MultiPlayerGameModeAccessor hand = (MultiPlayerGameModeAccessor) mc.gameMode;
		float own = hand != null && mc.gameMode.isDestroying() && info.pos().equals(hand.essentials$destroyBlockPos()) ? hand.essentials$destroyProgress() : 0;
		return Math.clamp(Math.max(service, own), 0, 1);
	}

	// ---- layout -----------------------------------------------------------------------------------------------------

	private float pad() {
		return 5 * scale.getFloat();
	}

	private float iconSize() {
		return 18 * scale.getFloat();
	}

	private float lineSize(Canvas c) {
		return fontSize(c) * 0.9f;
	}

	/** The break bar's height, and the gap above it. */
	private float barHeight() {
		return 2.5f * scale.getFloat();
	}

	/** At least this wide, so the card reads as a strip rather than a block of text. */
	private float minWidth() {
		return 130 * scale.getFloat();
	}

	private float detailsWidth(Canvas c, Info info) {
		float w = 0, ls = lineSize(c);
		for (int i = 0; i < info.lines().size(); i++) {
			Line l = info.lines().get(i);
			w += c.textWidth(FontFamily.SANS, ls, l.label() + " ") + c.textWidth(FontFamily.SANS_BOLD, ls, l.value());
			if (i > 0) w += c.textWidth(FontFamily.SANS, ls, GAP);
		}
		return w;
	}

	private float titleWidth(Canvas c, Info info) {
		// Room for the percentage beside the name while a break is shown.
		float percent = breakBar.get() && info.pos() != null ? c.textWidth(FontFamily.SANS_BOLD, lineSize(c), "100%") + pad() : 0;
		return c.textWidth(FontFamily.SANS_BOLD, fontSize(c), info.title()) + percent;
	}

	private float textHeight(Canvas c, Info info) {
		float h = c.textHeight(FontFamily.SANS_BOLD, fontSize(c));
		return info.lines().isEmpty() ? h : h + 1 + c.textHeight(FontFamily.SANS, lineSize(c));
	}

	private Rect size(Canvas c, Info info) {
		if (info == null) return new Rect(0, 0, 0, 0);
		boolean hasIcon = !info.icon().isEmpty();
		float text = Math.max(titleWidth(c, info), detailsWidth(c, info));
		float w = Math.max(minWidth(), pad() * 2 + (hasIcon ? iconSize() + pad() : 0) + text);
		float bar = breakBar.get() && info.pos() != null ? barHeight() + pad() * 0.6f : 0;
		float h = pad() * 2 + Math.max(hasIcon ? iconSize() : 0, textHeight(c, info)) + bar;
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
		float target = info != null ? breakProgress(info) : 0;
		// A new block starts its bar empty; more of the same block eases up, and a break given up drops at once.
		if (target < progress.get()) progress.snap(target);
		else progress.animateTo(target, 80, t.bezier());
		float done = progress.get();
		Rect r = size(c, show);
		float radius = t.rounding.get(), pad = pad(), icon = iconSize(), bar = breakBar.get() && show.pos() != null ? barHeight() + pad * 0.6f : 0;
		// Grow from the side the element is anchored to.
		float x = alignRight() ? w - r.w() : 0;
		c.push();
		c.alpha(alpha);
		if (background.get()) {
			if (t.blur.get()) c.backdrop(x, 0, r.w(), r.h(), radius, 1);
			c.roundRect(x, 0, r.w(), r.h(), radius, t.windowBackground.argb());
			if (t.borderSize.get() > 0) {
				// The gradient turns while a break is under way: the card shows the work going on.
				float angle = t.borderAngle.getFloat() + (done > 0 ? (System.nanoTime() / 1e9f / BORDER_TURN_SECONDS) % 1 * 360 : 0);
				c.gradientOutline(x, 0, r.w(), r.h(), radius, t.borderSize.get(), t.activeBorderFrom.argb(), t.activeBorderTo.argb(), angle);
			}
		}
		float content = r.h() - bar;
		float tx = x + pad;
		if (!show.icon().isEmpty()) {
			c.item(show.icon(), tx, (content - icon) / 2, icon);
			tx += icon + pad;
		}
		float ty = (content - textHeight(c, show)) / 2, ls = lineSize(c);
		c.text(FontFamily.SANS_BOLD, fontSize(c), show.title(), tx, ty, show.titleColor());
		if (done > 0) {
			String percent = Math.round(done * 100) + "%";
			float pw = c.textWidth(FontFamily.SANS_BOLD, ls, percent);
			float py = ty + (c.textHeight(FontFamily.SANS_BOLD, fontSize(c)) - c.textHeight(FontFamily.SANS_BOLD, ls)) / 2;
			c.text(FontFamily.SANS_BOLD, ls, percent, x + r.w() - pad - pw, py, t.accent.argb());
		}
		ty += c.textHeight(FontFamily.SANS_BOLD, fontSize(c)) + 1;
		float lx = tx;
		for (int i = 0; i < show.lines().size(); i++) {
			Line l = show.lines().get(i);
			if (i > 0) lx += c.text(FontFamily.SANS, ls, GAP, lx, ty, t.textDim.argb());
			lx += c.text(FontFamily.SANS, ls, l.label() + " ", lx, ty, t.textDim.argb());
			lx += c.text(FontFamily.SANS_BOLD, ls, l.value(), lx, ty, l.color());
		}
		if (bar > 0) {
			float bx = x + pad, bw = r.w() - pad * 2, by = r.h() - pad - barHeight(), bh = barHeight();
			c.roundRect(bx, by, bw, bh, bh / 2, ColorUtil.withAlpha(t.textDim.argb(), 50));
			if (done > 0) c.gradientRect(bx, by, Math.max(bh, bw * done), bh, bh / 2, t.activeBorderFrom.argb(), t.activeBorderTo.argb(), 0);
		}
		c.pop();
	}
}
