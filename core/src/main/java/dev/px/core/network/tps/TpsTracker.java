package dev.px.core.network.tps;

import dev.px.core.util.Validate;
import dev.px.core.util.collect.RollingAverage;

/**
 * Estimates the server's tick rate from its clock updates.
 *
 * <p>A server that tells the client its world age every so often is saying how
 * many ticks passed (the age moved on by that much) and, by when the update
 * arrives, how long they took:
 *
 * <pre>
 * tps = ticks elapsed / seconds elapsed
 * </pre>
 *
 * <p>No number about any game is needed for that, and none is assumed: not the
 * rate the server should run at, nor how often it sends its clock. Both are
 * measured. {@link #setTargetTps} is yours to give, and only caps the estimate.
 *
 * <p><b>Smoothing</b> is a ratio of sums over the last {@link #DEFAULT_WINDOW}
 * updates &mdash; total ticks over total time &mdash; not an average of each
 * update's rate. The difference matters when the network bunches packets: an
 * update held back 0.9s arrives 1.9s after the one before it, and the next 0.1s
 * after that. Averaged, those rates are wildly apart; summed, they are the ticks
 * that passed in the time that passed, which is the truth.
 *
 * <p><b>Stalls</b> show up before the next update does. If the server freezes,
 * no update arrives to report it, so {@link #getTps} also bounds the estimate by
 * the update now overdue: once it is later than updates have been arriving, the
 * ticks it would have reported are spread over the time actually waited.
 *
 * <p>This is the whole algorithm and it needs no bus, no service and no game:
 * call {@link #update} from wherever the clock packet is seen. {@link TpsService}
 * is this, wired to {@code PacketEvent}.
 *
 * <p>Thread-safe: updates arrive on the network thread, reads come from the HUD.
 */
public final class TpsTracker {

    /** Updates to smooth over. A tuning knob, not a fact about any game. */
    public static final int DEFAULT_WINDOW = 10;

    /**
     * An update carrying more than this many times the ticks updates have been
     * carrying is a different world, not elapsed time: it re-baselines rather than
     * reporting a spike. Measured against the server's own updates, so it assumes
     * nothing about how often a server sends them. A tuning knob, not a fact about
     * any game.
     */
    private static final double LEAP_FACTOR = 10d;

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final RollingAverage ticks;
    private final RollingAverage seconds;
    private double targetTps = Double.NaN;
    private long lastNanos = Long.MIN_VALUE;
    private long lastAge = -1L;
    private long lastTicks;
    private double lastSample = Double.NaN;

    public TpsTracker() {
        this(DEFAULT_WINDOW);
    }

    /** @param window how many updates to smooth over */
    public TpsTracker(int window) {
        this.ticks = RollingAverage.of(window);
        this.seconds = RollingAverage.of(window);
    }

    /**
     * Records a clock update.
     *
     * @param nanos when it arrived, on any monotonic clock
     * @param worldAge the world's total age in ticks; an update with a negative
     *        age says nothing about ticks and is ignored
     */
    public synchronized void update(long nanos, long worldAge) {
        if (worldAge < 0) {
            return;
        }
        if (lastNanos == Long.MIN_VALUE) {
            baseline(nanos, worldAge);
            return;
        }
        long elapsedTicks = worldAge - lastAge;
        if (elapsedTicks <= 0) {
            // Went backwards, or a duplicate: a new world, or the age was set.
            baseline(nanos, worldAge);
            return;
        }
        double elapsedSeconds = (nanos - lastNanos) / NANOS_PER_SECOND;
        if (elapsedSeconds <= 0d) {
            // Two updates in the same instant: keep the ticks, wait for time to pass.
            lastAge = worldAge;
            return;
        }
        if (hasEstimateLocked() && elapsedTicks > ticks.average() * LEAP_FACTOR) {
            // Far more ticks than updates have been carrying: the age leapt, so
            // this is a different world or a set clock, not elapsed time.
            baseline(nanos, worldAge);
            return;
        }
        double rate = elapsedTicks / elapsedSeconds;
        ticks.push(elapsedTicks);
        seconds.push(elapsedSeconds);
        lastSample = cap(rate);
        lastTicks = elapsedTicks;
        lastNanos = nanos;
        lastAge = worldAge;
    }

    /**
     * @param nowNanos the current time, on the clock {@link #update} is given
     * @return the smoothed tick rate, bounded by any update now overdue and never
     *         above {@link #getTargetTps()} when you set one. Before there is an
     *         estimate, the target, or NaN with no target: nothing is assumed
     */
    public synchronized double getTps(long nowNanos) {
        if (!hasEstimateLocked()) {
            return targetTps;
        }
        double tps = cap(ticks.sum() / seconds.sum());
        double sinceLast = (nowNanos - lastNanos) / NANOS_PER_SECOND;
        double expected = seconds.average();
        if (sinceLast > expected) {
            tps = Math.min(tps, lastTicks / sinceLast);
        }
        return tps;
    }

    /** @return the rate the most recent update implied, or NaN before one has */
    public synchronized double getLastSample() {
        return lastSample;
    }

    /** @return whether at least two updates have arrived, which is what one measurement takes */
    public synchronized boolean hasEstimate() {
        return hasEstimateLocked();
    }

    /** @return milliseconds since the last update, or -1 before the first */
    public synchronized long getMillisSinceUpdate(long nowNanos) {
        return lastNanos == Long.MIN_VALUE ? -1L : (nowNanos - lastNanos) / 1_000_000L;
    }

    /** @return the rate the server is meant to run at, or NaN when you have not said */
    public synchronized double getTargetTps() {
        return targetTps;
    }

    public synchronized boolean hasTargetTps() {
        return !Double.isNaN(targetTps);
    }

    /**
     * @param targetTps what the server is meant to run at. The estimate never
     *        reports above it, which keeps network bunching from reading as a
     *        server running fast; and it is what {@link #getTps} reports before
     *        there is a measurement. Change it whenever the server says its rate
     *        changed
     */
    public synchronized void setTargetTps(double targetTps) {
        Validate.check(targetTps > 0d, "target TPS must be positive");
        this.targetTps = targetTps;
    }

    /** Forgets the target: the estimate is then uncapped, and NaN until measured. */
    public synchronized void clearTargetTps() {
        this.targetTps = Double.NaN;
    }

    /** Forgets everything measured, but not the target. For leaving a server. */
    public synchronized void reset() {
        ticks.clear();
        seconds.clear();
        lastNanos = Long.MIN_VALUE;
        lastAge = -1L;
        lastTicks = 0L;
        lastSample = Double.NaN;
    }

    private double cap(double rate) {
        return Double.isNaN(targetTps) ? rate : Math.min(targetTps, rate);
    }

    private boolean hasEstimateLocked() {
        return ticks.count() > 0;
    }

    private void baseline(long nanos, long worldAge) {
        ticks.clear();
        seconds.clear();
        lastNanos = nanos;
        lastAge = worldAge;
        lastTicks = 0L;
        lastSample = Double.NaN;
    }
}
