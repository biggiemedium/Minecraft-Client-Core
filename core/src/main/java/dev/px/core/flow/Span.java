package dev.px.core.flow;

import dev.px.core.util.Validate;

/**
 * A length of time: a number of ticks, or of seconds.
 *
 * <pre>{@code
 * step.timeout(Span.ticks(40));
 * flow.stuckAfter(Span.seconds(30), recover());
 * memory.remember(UNMINABLE, pos, true, Span.seconds(300));
 * }</pre>
 *
 * <p>No tick rate is assumed, so the two are never converted into each other:
 * ticks are counted as they happen, and seconds are wall-clock time. Use ticks
 * for anything that should keep pace with the game, and seconds for human-scale
 * waits that should not stretch when the server lags.
 *
 * <p>Immutable.
 */
public final class Span {

    private final long ticks;
    private final long millis;

    private Span(long ticks, long millis) {
        this.ticks = ticks;
        this.millis = millis;
    }

    public static Span ticks(long ticks) {
        Validate.check(ticks >= 0, "a span cannot be negative, got " + ticks + " ticks");
        return new Span(ticks, -1L);
    }

    public static Span seconds(double seconds) {
        Validate.check(seconds >= 0d, "a span cannot be negative, got " + seconds + " seconds");
        return new Span(-1L, Math.round(seconds * 1000d));
    }

    public static Span millis(long millis) {
        Validate.check(millis >= 0, "a span cannot be negative, got " + millis + " ms");
        return new Span(-1L, millis);
    }

    /** @return whether this is counted in ticks rather than wall-clock time */
    public boolean isTicks() {
        return ticks >= 0;
    }

    /** @return the ticks, or -1 for a span in wall-clock time */
    public long getTicks() {
        return ticks;
    }

    /** @return the milliseconds, or -1 for a span in ticks */
    public long getMillis() {
        return millis;
    }

    /**
     * @return whether this long has passed between a start and now, each given
     *         as a tick count and a {@code System.nanoTime()}-style clock
     */
    public boolean hasPassed(long startTick, long startNanos, long nowTick, long nowNanos) {
        if (isTicks()) {
            return nowTick - startTick >= ticks;
        }
        return (nowNanos - startNanos) / 1_000_000L >= millis;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Span)) {
            return false;
        }
        Span span = (Span) other;
        return ticks == span.ticks && millis == span.millis;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(ticks) * 31 + Long.hashCode(millis);
    }

    @Override
    public String toString() {
        if (isTicks()) {
            return ticks + (ticks == 1 ? " tick" : " ticks");
        }
        return millis % 1000L == 0L ? (millis / 1000L) + "s" : millis + "ms";
    }
}
