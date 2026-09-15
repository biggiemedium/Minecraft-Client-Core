package dev.px.core.command;

import dev.px.core.platform.Platform;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Locale;

/**
 * One command invocation: the arguments, and the way to answer.
 *
 * <p>Argument access is checked and typed. The old command interface handed over
 * a raw {@code String[]} and every implementation re-wrote the same index and
 * parse guards, usually getting the out-of-range case wrong. Here a bad argument
 * throws {@link CommandException}, which the registry catches and turns into a
 * usage message, so a command body can read arguments as if they are valid.
 */
@Getter
@RequiredArgsConstructor
public final class CommandContext {

    private final Command command;
    private final String[] args;
    private final String raw;
    private final Platform platform;

    public int size() {
        return args.length;
    }

    public boolean isEmpty() {
        return args.length == 0;
    }

    public boolean has(int index) {
        return index >= 0 && index < args.length;
    }

    /** @throws CommandException if the argument is missing */
    public String get(int index) {
        if (!has(index)) {
            throw new CommandException("Missing argument " + (index + 1));
        }
        return args[index];
    }

    public String get(int index, String fallback) {
        return has(index) ? args[index] : fallback;
    }

    /** @return the argument lowercased, for comparing against a literal subcommand. */
    public String word(int index) {
        return get(index).toLowerCase(Locale.ROOT);
    }

    /** @throws CommandException if the argument is missing or not a number */
    public int getInt(int index) {
        String value = get(index);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CommandException(value + " is not a whole number");
        }
    }

    public double getDouble(int index) {
        String value = get(index);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new CommandException(value + " is not a number");
        }
    }

    public boolean getBoolean(int index) {
        String value = word(index);
        switch (value) {
            case "true": case "on": case "yes": case "enable": case "1":
                return true;
            case "false": case "off": case "no": case "disable": case "0":
                return false;
            default:
                throw new CommandException(value + " is not true or false");
        }
    }

    /** @return arguments from {@code index} onwards joined with spaces. For trailing free text. */
    public String rest(int index) {
        if (!has(index)) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(args, index, args.length));
    }

    /** Fails the command with a message the user sees. */
    public void fail(String message) {
        throw new CommandException(message);
    }

    /** Fails with the command's declared usage string. */
    public void failUsage() {
        throw new CommandException("Usage: " + command.getUsage());
    }

    /** Prints a client-side reply. */
    public void reply(String message) {
        platform.printMessage(message);
    }
}
