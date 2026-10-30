package dev.myriadclient.essentials.hud;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.ColorSetting;
import dev.myriadclient.api.setting.DoubleSetting;
import dev.myriadclient.api.setting.EnumSetting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.hud.HudPanel;
import dev.myriadclient.api.ui.hud.HudStyle;
import dev.myriadclient.api.ui.hud.ItemHud;
import dev.myriadclient.api.util.ItemInfo;
import dev.myriadclient.api.util.Slots;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * What you hold and wear: your main hand and off hand, then your armour, helmet first, each with its durability as the
 * uses left, a percentage or a bar. Stacks in your hands show their count. The text is in the durability's colour or one
 * of your own. Registered under the id {@code myriad-essentials:armor}, its name before, so HUDs placed then keep it.
 */
public final class GearPanel extends HudPanel {
	public enum Layout {
		HORIZONTAL, VERTICAL
	}

	public enum TextColor {
		DURABILITY, CUSTOM
	}

	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private final EnumSetting<Layout> layout = sgGeneral.oneOf("Layout", Layout.HORIZONTAL).build();
	private final BoolSetting mainHand = sgGeneral.bool("Main Hand").description("Show what's in your main hand, first.").defaultValue(true).build();
	private final BoolSetting offHand = sgGeneral.bool("Off Hand").description("Show what's in your off hand, after the main hand.").defaultValue(true).build();
	private final EnumSetting<ItemHud.Durability> durability = sgGeneral.oneOf("Durability", ItemHud.Durability.NUMBER)
		.description("Number: the uses left. Percent, a bar, both, or none.").build();
	private final BoolSetting hideUndamaged = sgGeneral.bool("Hide Undamaged").description("No durability on gear that hasn't taken any.").defaultValue(true)
		.visible(() -> durability.get() != ItemHud.Durability.NONE).build();
	private final DoubleSetting textScale = sgGeneral.decimal("Text Scale").description("Size of the durability, relative to the HUD text.").defaultValue(0.8)
		.range(0.5, 1.5).decimals(2).visible(() -> ItemHud.showsPercent(durability.get())).build();
	private final EnumSetting<TextColor> textColor = sgGeneral.oneOf("Text Color", TextColor.DURABILITY)
		.description("Durability: green to red as it wears, counts in the theme's text. Custom: one colour for all of it.").build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.TEXT))
		.visible(() -> textColor.get() == TextColor.CUSTOM).build();
	private final BoolSetting skipEmpty = sgGeneral.bool("Skip Empty").description("Close up gaps left by empty slots.").build();
	private final BoolSetting avoidAir = sgGeneral.bool("Avoid Air Bar").description("Move up while the air bubbles are showing, like vanilla HUD rows.").defaultValue(true).build();
	private final BoolSetting shadow = sgGeneral.bool("Shadow").defaultValue(true).build();

	public GearPanel() {
		super("Gear", "\uf132");
	}

	private List<ItemStack> stacks() {
		List<ItemStack> list = new ArrayList<>(6);
		if (mc.player == null) {
			// Preview while editing the HUD from the title screen.
			if (mainHand.get()) list.add(Items.DIAMOND_SWORD.getDefaultInstance());
			if (offHand.get()) list.add(Items.TOTEM_OF_UNDYING.getDefaultInstance());
			list.add(Items.DIAMOND_HELMET.getDefaultInstance());
			list.add(Items.DIAMOND_CHESTPLATE.getDefaultInstance());
			list.add(Items.DIAMOND_LEGGINGS.getDefaultInstance());
			list.add(Items.DIAMOND_BOOTS.getDefaultInstance());
			return list;
		}
		// What each slot shows: a masked swap (a silent slot switch, No Durability's chestplate swaps) doesn't flicker it.
		if (mainHand.get()) add(list, Myriad.inventory().shown(mc.player.getInventory().getSelectedSlot()));
		if (offHand.get()) add(list, Myriad.inventory().shown(Slots.OFF_HAND));
		for (EquipmentSlot slot : ARMOR) add(list, Myriad.inventory().shown(Slots.FEET + slot.getIndex()));
		return list;
	}

	private void add(List<ItemStack> list, ItemStack stack) {
		if (!stack.isEmpty() || !skipEmpty.get()) list.add(stack);
	}

	/** Slots shown when none are skipped: the hands switched on, and the four armour slots. */
	private int slots() {
		return (mainHand.get() ? 1 : 0) + (offHand.get() ? 1 : 0) + ARMOR.length;
	}

	private float slot() {
		return 18 * scale.getFloat();
	}

	private float percentHeight(Canvas c) {
		return ItemHud.showsPercent(durability.get()) ? c.textHeight(FontFamily.SANS, textSize(c)) : 0;
	}

	private float textSize(Canvas c) {
		return c.defaultFontSize() * textScale.getFloat() * scale.getFloat();
	}

	@Override
	public Rect preferredSize(Canvas c) {
		int n = Math.max(1, skipEmpty.get() ? stacks().size() : slots());
		float slot = slot(), ph = percentHeight(c);
		return layout.get() == Layout.HORIZONTAL ? new Rect(0, 0, slot * n, slot + ph) : new Rect(0, 0, slot, (slot + ph) * n);
	}

	@Override
	public void render(Canvas c, float w, float h, float mx, float my) {
		float slot = slot(), size = 16 * scale.getFloat(), pad = (slot - size) / 2, ph = percentHeight(c);
		// Vanilla draws the air bubbles one row above the hunger bar; step out of their way while they show.
		float yShift = avoidAir.get() && mc.player != null && mc.player.getAirSupply() < mc.player.getMaxAirSupply() ? -10 : 0;
		List<ItemStack> stacks = stacks();
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack stack = stacks.get(i);
			if (stack.isEmpty()) continue;
			float x = layout.get() == Layout.HORIZONTAL ? i * slot : 0;
			float y = (layout.get() == Layout.HORIZONTAL ? 0 : i * (slot + ph)) + yShift;
			float ix = x + pad, iy = y + ph + pad;
			c.item(stack, ix, iy, size, false);
			// A stack in a hand (blocks, crystals, totems) shows its count; gear its durability, below.
			if (!stack.isDamageableItem()) {
				if (stack.getCount() > 1) count(c, stack.getCount(), ix, iy, size);
				continue;
			}
			if (durability.get() == ItemHud.Durability.NONE || hideUndamaged.get() && !stack.isDamaged()) continue;
			if (durability.get() == ItemHud.Durability.BAR || durability.get() == ItemHud.Durability.BOTH) ItemHud.bar(c, stack, ix, iy, size);
			if (ItemHud.showsPercent(durability.get())) durabilityText(c, stack, ix, y, size);
		}
	}

	/** The uses left or the percentage, centred over the item at x, top at y. */
	private void durabilityText(Canvas c, ItemStack stack, float x, float y, float size) {
		float p = ItemHud.percent(stack), ts = textSize(c);
		String text = durability.get() == ItemHud.Durability.NUMBER ? String.valueOf(ItemInfo.durability(stack)) : Math.round(p * 100) + "%";
		int argb = textColor.get() == TextColor.CUSTOM ? color.argb() : ItemHud.durabilityColor(p);
		HudStyle.draw(c, FontFamily.SANS, ts, text, x + (size - c.textWidth(FontFamily.SANS, ts, text)) / 2, y, argb, shadow.get());
	}

	/** A stack's count in the item's bottom right corner, as Minecraft puts it. */
	private void count(Canvas c, int n, float x, float y, float size) {
		String text = String.valueOf(n);
		float ts = c.defaultFontSize() * scale.getFloat();
		int argb = textColor.get() == TextColor.CUSTOM ? color.argb() : Myriad.ui().theme().text.argb();
		HudStyle.draw(c, FontFamily.SANS_BOLD, ts, text, x + size - c.textWidth(FontFamily.SANS_BOLD, ts, text),
			y + size - c.textHeight(FontFamily.SANS_BOLD, ts) + 1, argb, shadow.get());
	}
}
