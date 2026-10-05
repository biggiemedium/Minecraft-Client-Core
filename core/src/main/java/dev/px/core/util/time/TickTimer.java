package dev.px.core.util.time;

import dev.px.core.math.MathUtil;
import dev.px.core.util.Validate;

/**
 * A countdown measured in game ticks rather than milliseconds.
 *
 * <p>{@link dev.px.core.math.Stopwatch} answers "has enough real time passed",
 * which is right for anything a user perceives: an animation, a cooldown on a
 * key, a rate limit. This answers "have enough ticks passed", which is right for
 * anything the server perceives. The two are not the same clock. A server at 12
 * TPS runs a 20-tick delay in 1.6 seconds of wall time, and a module that paced
 * itself with a stopwatch would fire early on every lagging server and be
 * obvious for it.
 *
 * <p>Advance it once per tick event and read the result:
 *
 * <pre>{@code
 * private final TickTimer delay = TickTimer.every(20);
 *
 * @Subscribe
 * public void onTick(TickEvent event) {
 *     if (delay.tick()) {
 *         doSomethingOncePerSecond();
 *     }
 * }
 * }</pre>
 *
 * <p><b>Complexity.</b> Every method is O(1); {@link #progress()} is
 * {@code ticks / period}.
 *
 * <p>A timer that is never ticked never fires, which is the desired behaviour
 * when the player leaves the world: the count freezes rather than a second's
 * worth of ticks arriving at once on the next join.
 */
public final class TickTimer {

    private final int period;

    private int ticks;

    private TickTimer(int period) {
        this.period = period;
    }

    /**
     * A timer that fires every {@code period} ticks.
     *
     * @throws IllegalArgumentException if the period is not positive
     */
    public static TickTimer every(int period) {
        Validate.check(period > 0, "period must be positive");
        return new TickTimer(period);
    }

    /**
     * A free-running counter that never fires on its own.
     *
     * <p>For the case where the interval is not fixed &mdash; a randomised delay,
     * or one read from a setting each time. Advance with {@link #tick()} and test
     * with {@link #tryConsume(int)}.
     */
    public static TickTimer counting() {
        return new TickTimer(0);
    }

    /**
     * Advances by one tick.
     *
     * @return whether a fixed period elapsed on this tick, resetting if so.
     *         Always {@code false} for a {@link #counting()} timer
     */
    public boolean tick() {
        ticks++;
        if (period > 0 && ticks >= period) {
            ticks = 0;
            return true;
        }
        return false;
    }

    /** @return whether at least {@code count} ticks have accumulated. Does not reset. */
    public boolean elapsed(int count) {
        return ticks >= count;
    }

    /** @return whether {@code count} ticks have accumulated, resetting if so. */
    public boolean tryConsume(int count) {
        if (elapsed(count)) {
            reset();
            return true;
        }
        return false;
    }

    public void reset() {
        ticks = 0;
    }

    /** Sets the count so the next {@link #tick()} on a fixed-period timer fires. */
    public void expire() {
        ticks = Math.max(0, period - 1);
    }

    public int getTicks() {
        return ticks;
    }

    public int getPeriod() {
        return period;
    }

    /** @return progress towards the period, 0..1. For drawing a cooldown bar. */
    public float progress() {
        return period <= 0 ? 0f : MathUtil.saturate(ticks / (float) period);
    }

    @Override
    public String toString() {
        return period > 0 ? ticks + "/" + period + " ticks" : ticks + " ticks";
    }
}
