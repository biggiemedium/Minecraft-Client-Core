package dev.px.core.integration;

import lombok.Builder;
import lombok.Getter;

/**
 * What to show on an external presence service such as Discord.
 *
 * <p>An immutable snapshot rather than a mutable status object, so the service
 * can compare against the last published value and skip the update when nothing
 * changed. Presence APIs rate-limit, and a client that pushes every tick gets
 * throttled.
 */
@Getter
@Builder
public final class RichPresence {

    /** The upper line, e.g. the current activity. */
    private final String details;

    /** The lower line, e.g. the server or game mode. */
    private final String state;

    private final String largeImageKey;
    private final String largeImageText;
    private final String smallImageKey;
    private final String smallImageText;

    /** Epoch millis the activity began, so the service can show elapsed time. Zero for none. */
    private final long startedAt;

    /** Suppresses the update if the previous presence is identical. */
    public boolean matches(RichPresence other) {
        return other != null
                && eq(details, other.details)
                && eq(state, other.state)
                && eq(largeImageKey, other.largeImageKey)
                && eq(smallImageKey, other.smallImageKey)
                && startedAt == other.startedAt;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
