package dev.px.core.command;

/**
 * A command failed in a way the user should be told about.
 *
 * <p>Carries no stack trace: this is a message for the chat window, not a fault
 * report, and building a trace for a mistyped argument is wasted work.
 */
public final class CommandException extends RuntimeException {

    public CommandException(String message) {
        super(message, null, false, false);
    }
}
