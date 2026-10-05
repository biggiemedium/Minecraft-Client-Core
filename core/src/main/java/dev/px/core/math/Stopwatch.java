package dev.px.core.math;

/**
 * A monotonic elapsed-time check, for cooldowns and rate limits.
 *
 * <p>Replaces the old {@code TimerUtil}. Two differences matter. It uses
 * {@link System#nanoTime()} rather than {@code currentTimeMillis()}, which can
 * jump backwards when the system clock is adjusted and silently stall a
 * cooldown. And {@link #elapsed(long)} does not reset, so a check no longer has
 * a side effect; {@link #tryConsume(long)} is the combined form when that is
 * what you actually want.
 */
public final class Stopwatch {

    private long mark = System.nanoTime();

    /** Creates a stopwatch that reads as having just been reset. */
    public static Stopwatch started() {
        return new Stopwatch();
    }

    /** Creates a stopwatch that reads as long expired, so the first check passes. */
    public static Stopwatch expired() {
        Stopwatch stopwatch = new Stopwatch();
        stopwatch.mark = 0L;
        return stopwatch;
    }

    public void reset() {
        mark = System.nanoTime();
    }

    public long elapsedMillis() {
        return (System.nanoTime() - mark) / 1_000_000L;
    }

    public double elapsedSeconds() {
        return (System.nanoTime() - mark) / 1_000_000_000d;
    }

    /** @return whether at least {@code millis} have passed. Does not reset. */
    public boolean elapsed(long millis) {
        return elapsedMillis() >= millis;
    }

    /**
     * @return whether {@code millis} have passed, resetting if so.
     *
     * <p>The idiom for a rate-limited action:
     * {@code if (attackTimer.tryConsume(delay)) { attack(); }}
     */
    public boolean tryConsume(long millis) {
        if (elapsed(millis)) {
            reset();
            return true;
        }
        return false;
    }

    /** @return progress towards {@code millis}, 0..1. For drawing a cooldown bar. */
    public float progress(long millis) {
        return millis <= 0L ? 1f : MathUtil.saturate(elapsedMillis() / (float) millis);
    }
}
