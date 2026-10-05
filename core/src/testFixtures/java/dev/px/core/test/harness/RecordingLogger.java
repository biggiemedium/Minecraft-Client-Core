package dev.px.core.test.harness;

import dev.px.core.util.CoreLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link CoreLogger} that keeps what it was told.
 *
 * <p>Needed because half of what {@link dev.px.core.concurrent.ThreadService}
 * promises is about the log: that a failed task is reported, and that an
 * ordinary cancellation is <em>not</em>. Neither is observable any other way,
 * and the second one was a real bug &mdash; an error and a stack trace on every
 * module toggle.
 *
 * <p>Every method is synchronized: worker threads write here while the test
 * thread reads.
 */
public final class RecordingLogger implements CoreLogger {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /** Mirror to stdout as well, so a failing run is still diagnosable from the log. */
    private final boolean echo;

    public RecordingLogger() {
        this(false);
    }

    public RecordingLogger(boolean echo) {
        this.echo = echo;
    }

    @Override
    public synchronized void info(String message) {
    }

    @Override
    public synchronized void warn(String message) {
        warnings.add(message);
        if (echo) {
            System.out.println("      [warn] " + message);
        }
    }

    @Override
    public synchronized void error(String message) {
        error(message, null);
    }

    @Override
    public synchronized void error(String message, Throwable thrown) {
        errors.add(message);
        if (echo) {
            System.out.println("      [error] " + message
                    + (thrown == null ? "" : " (" + thrown.getClass().getSimpleName() + ")"));
        }
    }

    @Override
    public synchronized void debug(String message) {
    }

    public synchronized List<String> getErrors() {
        return Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public synchronized int errorCount() {
        return errors.size();
    }

    public synchronized int warningCount() {
        return warnings.size();
    }

    /** @return whether any error message contains {@code fragment}. */
    public synchronized boolean loggedError(String fragment) {
        for (String message : errors) {
            if (message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether any warning contains {@code fragment}. */
    public synchronized boolean loggedWarning(String fragment) {
        for (String message : warnings) {
            if (message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        errors.clear();
        warnings.clear();
    }
}
