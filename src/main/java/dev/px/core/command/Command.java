package dev.px.core.command;

import dev.px.core.registry.Named;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A chat command.
 *
 * <p>Identity comes from {@link CommandInfo}, so a subclass is the annotation
 * plus {@link #execute}. Argument validation lives in {@link CommandContext},
 * and throwing {@link CommandException} from anywhere in the body reports the
 * message to the user, so the happy path needs no error plumbing.
 *
 * <pre>{@code
 * @CommandInfo(name = "toggle", usage = "toggle <module>")
 * public final class ToggleCommand extends Command {
 *
 *     @Override
 *     public void execute(CommandContext context) {
 *         Module module = Core.modules().find(context.get(0))
 *                 .orElseThrow(() -> new CommandException("No such module"));
 *         module.toggle();
 *         context.reply(module.getName() + " toggled");
 *     }
 * }
 * }</pre>
 */
@Getter
public abstract class Command implements Named {

    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String usage;

    protected Command() {
        CommandInfo info = getClass().getAnnotation(CommandInfo.class);
        if (info == null) {
            throw new IllegalStateException(getClass().getName()
                    + " extends Command but is missing its @CommandInfo annotation");
        }
        this.name = info.name();
        this.aliases = Collections.unmodifiableList(Arrays.asList(info.aliases()));
        this.description = info.description();
        // Default the usage to the bare name so an unannotated usage still reads sensibly.
        this.usage = info.usage().isEmpty() ? info.name() : info.usage();
    }

    /**
     * Runs the command.
     *
     * @throws CommandException to report a problem to the user
     */
    public abstract void execute(CommandContext context);

    /**
     * Suggestions for the argument at {@code index}, for tab completion.
     * Returns nothing by default.
     */
    public List<String> complete(CommandContext context, int index) {
        return Collections.emptyList();
    }

    public final boolean matches(String input) {
        if (name.equalsIgnoreCase(input)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(input)) {
                return true;
            }
        }
        return false;
    }
}
