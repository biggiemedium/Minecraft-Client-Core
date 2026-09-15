package dev.px.core.test.example;

import dev.px.core.Core;
import dev.px.core.command.Command;
import dev.px.core.command.CommandContext;
import dev.px.core.command.CommandException;
import dev.px.core.command.CommandInfo;
import dev.px.core.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * A command with arguments, a failure path, and tab completion.
 *
 * <p>Note the absence of error plumbing: {@link CommandContext} validates, and a
 * {@link CommandException} thrown anywhere in the body becomes a chat message.
 * The body only has to describe the happy path.
 */
@CommandInfo(
        name = "toggle",
        aliases = {"t"},
        description = "Toggles a module by name",
        usage = "toggle <module>")
public final class ExampleToggleCommand extends Command {

    @Override
    public void execute(CommandContext context) {
        if (context.isEmpty()) {
            context.failUsage();
        }
        // rest(0) rather than get(0), so multi-word names work without quoting.
        String name = context.rest(0);
        Module module = Core.modules().find(name)
                .orElseThrow(() -> new CommandException("No module called " + name));

        module.toggle();
        context.reply(module.getName() + (module.isEnabled() ? " enabled" : " disabled"));
    }

    @Override
    public List<String> complete(CommandContext context, int index) {
        List<String> names = new ArrayList<>();
        if (index == 0) {
            for (Module module : Core.modules()) {
                names.add(module.getName());
            }
        }
        return names;
    }
}
