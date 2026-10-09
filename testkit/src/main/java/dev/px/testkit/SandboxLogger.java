package dev.px.testkit;

import dev.px.core.util.CoreLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps everything a sandbox's services log, so a test can check what was said.
 *
 * <pre>{@code
 * assert sandbox.logger().hasWarning("without declaring it");
 * }</pre>
 */
public final class SandboxLogger implements CoreLogger {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> infos = new ArrayList<>();
    private boolean echo;

    /** Prints everything as well as keeping it, for a test being debugged. */
    public SandboxLogger echo(boolean echo) {
        this.echo = echo;
        return this;
    }

    @Override
    public void info(String message) {
        infos.add(message);
        print("INFO", message);
    }

    @Override
    public void warn(String message) {
        warnings.add(message);
        print("WARN", message);
    }

    @Override
    public void error(String message) {
        errors.add(message);
        print("ERROR", message);
    }

    @Override
    public void error(String message, Throwable thrown) {
        errors.add(message + ": " + thrown);
        print("ERROR", message + ": " + thrown);
    }

    @Override
    public void debug(String message) {
        print("DEBUG", message);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /** @return whether any warning contains {@code fragment} */
    public boolean hasWarning(String fragment) {
        for (String warning : warnings) {
            if (warning.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        warnings.clear();
        errors.clear();
        infos.clear();
    }

    private void print(String level, String message) {
        if (echo) {
            System.out.println("[sandbox/" + level + "] " + message);
        }
    }
}
