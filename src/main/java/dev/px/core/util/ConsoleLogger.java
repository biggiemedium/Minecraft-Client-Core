package dev.px.core.util;

import lombok.RequiredArgsConstructor;
import lombok.Setter;

/** Fallback {@link CoreLogger} writing to stdout/stderr with a bracketed prefix. */
@RequiredArgsConstructor
public final class ConsoleLogger implements CoreLogger {

    private final String prefix;

    /** Debug output is off by default so a shipped client stays quiet. */
    @Setter
    private boolean debugEnabled;

    @Override
    public void info(String message) {
        System.out.println(format("INFO", message));
    }

    @Override
    public void warn(String message) {
        System.out.println(format("WARN", message));
    }

    @Override
    public void error(String message) {
        System.err.println(format("ERROR", message));
    }

    @Override
    public void error(String message, Throwable thrown) {
        System.err.println(format("ERROR", message));
        if (thrown != null) {
            thrown.printStackTrace();
        }
    }

    @Override
    public void debug(String message) {
        if (debugEnabled) {
            System.out.println(format("DEBUG", message));
        }
    }

    private String format(String level, String message) {
        return "[" + prefix + "/" + level + "] " + message;
    }
}
