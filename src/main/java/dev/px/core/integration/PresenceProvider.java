package dev.px.core.integration;

/**
 * Publishes {@link RichPresence} to an external service.
 *
 * <p>An SPI because the implementation needs a native library the client may not
 * ship, and because the service differs. Core keeps the concept and leaves the
 * transport out.
 */
public interface PresenceProvider {

    /** Connects. Called off the game thread; may block. */
    void connect() throws Exception;

    void publish(RichPresence presence);

    void disconnect();

    boolean isConnected();
}
