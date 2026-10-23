package dev.myriadclient.essentials.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.myriadclient.api.command.Command;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Development only, for the Grim suite: {@code .printscene <x1> <y1> <z1> <x2> <y2> <z2> <dx> <dy> <dz>} saves the box
 * as the client sees it as a Litematica schematic and places it {@code dx dy dz} away, where the Printer then builds it;
 * the suite builds the reference with server commands and compares the two boxes on the server afterwards.
 * {@code .printscene clear} removes the placement. Works on any server, unlike {@code .printtest}.
 */
public final class PrintScene extends Command {
	private @Nullable SchematicPlacement placement;

	public PrintScene() {
		super("printscene", "Development: saves a box as a Litematica schematic and places it elsewhere, for the Printer to build.");
	}

	@Override
	public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
		b.then(literal("clear").executes(ctx -> {
			clear();
			info("Cleared");
			return SINGLE_SUCCESS;
		}));
		// Nine integers, the last one running the command.
		String[] names = {"x1", "y1", "z1", "x2", "y2", "z2", "dx", "dy", "dz"};
		var node = argument(names[8], IntegerArgumentType.integer()).executes(this::save);
		for (int i = 7; i >= 0; i--) node = argument(names[i], IntegerArgumentType.integer()).then(node);
		b.then(node);
	}

	private int save(CommandContext<SharedSuggestionProvider> ctx) {
		BlockPos a = new BlockPos(IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "y1"), IntegerArgumentType.getInteger(ctx, "z1"));
		BlockPos b = new BlockPos(IntegerArgumentType.getInteger(ctx, "x2"), IntegerArgumentType.getInteger(ctx, "y2"), IntegerArgumentType.getInteger(ctx, "z2"));
		BlockPos offset = new BlockPos(IntegerArgumentType.getInteger(ctx, "dx"), IntegerArgumentType.getInteger(ctx, "dy"), IntegerArgumentType.getInteger(ctx, "dz"));
		clear();
		AreaSelection area = new AreaSelection();
		area.addSubRegionBox(new Box(a, b, "printscene"), false);
		LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false), "Myriad",
			message -> info("Litematica: " + message));
		if (schematic == null) {
			error("Litematica didn't save the schematic");
			return SINGLE_SUCCESS;
		}
		BlockPos origin = BlockPos.min(a, b).offset(offset);
		placement = SchematicPlacement.createFor(schematic, origin, "printscene", true, true);
		DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
		info("Placed the schematic at " + origin.toShortString());
		return SINGLE_SUCCESS;
	}

	private void clear() {
		if (placement != null) DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
		placement = null;
	}
}
