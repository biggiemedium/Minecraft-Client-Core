package dev.px.core.util;

/**
 * Minimal logging seam.
 *
 * <p>Core avoids a hard dependency on log4j so it stays compilable without
 * Minecraft on the classpath. Adapters normally hand Core a delegate to the
 * game logger; {@link ConsoleLogger} covers the rest.
 */
public interface CoreLogger {

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable thrown);

    void debug(String message);
}
