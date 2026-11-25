package dev.myriadclient.essentials.util;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.command.arguments.Arguments;
import dev.myriadclient.api.command.arguments.ChoiceArgumentType;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.function.Consumer;

/**
 * Adds and removes a module's inputs and outputs (see {@link ContainerLinks}) by the container you look at, so it can
 * go on a macro or a wheel as well as in chat: {@code .containers AutoCraft input} adds it or takes it off (as Input
 * Bind does), {@code add}, {@code remove} or {@code clear} after it to say which. {@code .containers AutoCraft} says
 * how many it has.
 */
public final class ContainersCommand extends Command {
	public ContainersCommand() {
		super("containers", "Adds or removes the container you look at as a module's input or output (.containers AutoCraft input add).");
	}

	@Override
	public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
		b.then(argument("module", Arguments.choice(() -> ContainerLinks.byModule().keySet()))
			.executes(c -> {
				ContainerLinks l = links(c);
				info(l.module().name() + ": " + l.summary());
				return SINGLE_SUCCESS;
			})
			.then(side("input", true))
			.then(side("output", false)));
	}

	private static LiteralArgumentBuilder<SharedSuggestionProvider> side(String name, boolean input) {
		return literal(name)
			.executes(c -> run(c, l -> l.toggle(input)))
			.then(literal("add").executes(c -> run(c, l -> l.pick(input, true))))
			.then(literal("remove").executes(c -> run(c, l -> l.pick(input, false))))
			.then(literal("clear").executes(c -> run(c, l -> l.clear(input))));
	}

	private static int run(CommandContext<SharedSuggestionProvider> c, Consumer<ContainerLinks> action) {
		action.accept(links(c));
		return SINGLE_SUCCESS;
	}

	private static ContainerLinks links(CommandContext<SharedSuggestionProvider> c) {
		return ContainerLinks.byModule().get(ChoiceArgumentType.get(c, "module"));
	}
}
