package dev.px.core.command;

import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.ChatSendEvent;
import dev.px.core.platform.Platform;
import dev.px.core.registry.Registry;
import dev.px.core.service.Service;
import dev.px.core.setting.SettingHolder;
import dev.px.core.setting.impl.StringSetting;
import dev.px.core.util.CoreLogger;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The registered commands, and the chat hook that dispatches them.
 *
 * <p>The old {@code CommandManager} registered a chat listener whose body was
 * empty, so no command ever ran. Dispatch lives here: a message starting with
 * the prefix is parsed, cancelled so it never reaches the server, and routed.
 *
 * <p>Cancelling is the important part and is easy to get wrong in the other
 * order. The event is cancelled before the command body runs, so a command that
 * throws still cannot leak the raw text to the server.
 */
@Getter
public final class CommandRegistry extends Registry<Command> implements Service {

    private final EventBus bus;
    private final Platform platform;
    private final CoreLogger logger;

    private final Prefix prefix = new Prefix();

    public CommandRegistry(EventBus bus, Platform platform, CoreLogger logger) {
        this.bus = bus;
        this.platform = platform;
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "Commands";
    }

    @Override
    public void start() {
        bus.subscribe(this);
    }

    @Override
    public void stop() {
        bus.unsubscribe(this);
    }

    /** @return the command matching a name or alias. */
    public Optional<Command> resolve(String input) {
        for (Command command : all()) {
            if (command.matches(input)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }

    /**
     * Runs a command line that has already had its prefix stripped.
     *
     * @return whether a command was found and attempted
     */
    public boolean dispatch(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return false;
        }
        Optional<Command> found = resolve(parts[0]);
        if (!found.isPresent()) {
            platform.printMessage("Unknown command: " + parts[0]);
            return false;
        }
        Command command = found.get();
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        try {
            command.execute(new CommandContext(command, args, line, platform));
        } catch (CommandException e) {
            // Expected: a user-facing problem, reported as a message.
            platform.printMessage(e.getMessage());
        } catch (Exception e) {
            // Unexpected: a bug in the command, worth a log line and a stack trace.
            logger.error("Command " + command.getName() + " failed", e);
            platform.printMessage("Command failed: " + e.getMessage());
        }
        return true;
    }

    /** @return completion suggestions for a partially typed line. */
    public List<String> complete(String line) {
        List<String> suggestions = new ArrayList<>();
        String[] parts = line.split("\\s+", -1);
        if (parts.length <= 1) {
            for (Command command : all()) {
                if (command.getName().toLowerCase().startsWith(parts.length == 0 ? "" : parts[0].toLowerCase())) {
                    suggestions.add(command.getName());
                }
            }
            return suggestions;
        }
        resolve(parts[0]).ifPresent(command -> {
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            suggestions.addAll(command.complete(
                    new CommandContext(command, args, line, platform), args.length - 1));
        });
        return suggestions;
    }

    @Subscribe(priority = Priority.HIGHEST)
    private void onChatSend(ChatSendEvent event) {
        String message = event.getMessage();
        String token = prefix.value.get();
        if (token.isEmpty() || !message.startsWith(token)) {
            return;
        }
        // Cancel first: a command that throws must not fall through to the server.
        event.cancel();
        dispatch(message.substring(token.length()));
    }

    @Override
    protected String describe() {
        return "command";
    }

    /** The command prefix, exposed as a settings section so it persists and is editable. */
    public static final class Prefix extends SettingHolder {

        private final StringSetting value = text("Command Prefix", ".")
                .maxLength(3)
                .validatedBy(candidate -> !candidate.isEmpty())
                .describe("Character that marks a chat message as a client command");

        @Override
        public String getName() {
            return "Commands";
        }

        public String get() {
            return value.get();
        }

        public void set(String token) {
            value.set(token);
        }
    }
}
