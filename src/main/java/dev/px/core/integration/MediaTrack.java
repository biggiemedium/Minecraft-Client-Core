package dev.px.core.integration;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A track reported by a media provider, for a now-playing HUD element.
 *
 * <p>Positions are a timestamp plus an offset rather than a live value, so the
 * element can interpolate smoothly between the provider's infrequent polls
 * instead of stepping once a second.
 */
@Getter
@RequiredArgsConstructor
public final class MediaTrack {

    private final String title;
    private final String artist;
    private final String album;
    private final long durationMillis;

    /** Playback position at {@link #sampledAt}. */
    private final long positionMillis;

    private final long sampledAt;
    private final boolean playing;

    /** Optional cover art URL, or empty. */
    private final String artworkUrl;

    /** @return the position now, extrapolated from the last sample. */
    public long getCurrentPositionMillis() {
        if (!playing) {
            return positionMillis;
        }
        return Math.min(durationMillis, positionMillis + (System.currentTimeMillis() - sampledAt));
    }

    /** @return playback progress 0..1, for a seek bar. */
    public float getProgress() {
        return durationMillis <= 0L ? 0f : getCurrentPositionMillis() / (float) durationMillis;
    }

    public String getLabel() {
        return artist == null || artist.isEmpty() ? title : artist + " - " + title;
    }
}
