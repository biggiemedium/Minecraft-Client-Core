package dev.px.core.integration;

/**
 * Reports what is currently playing.
 *
 * <p>An SPI so a client can back it with Spotify, a local player, or the OS media
 * session without Core carrying an API client or its credentials.
 */
public interface MediaProvider {

    String getName();

    /** Called off the game thread on a poll interval; may block on a network call. */
    MediaTrack poll() throws Exception;

    /** @return whether the provider is configured and usable. */
    boolean isAvailable();

    default void skipNext() {
    }

    default void skipPrevious() {
    }

    default void togglePlayback() {
    }
}
