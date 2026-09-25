package dev.px.core.network.tps;

import dev.px.core.util.Validate;
import dev.px.core.util.collect.RollingAverage;

/**
 * Estimates the server's tick rate from its world-clock updates.
 *
 * <p>A vanilla server sends the world time every twenty ticks. At full speed
 * that is one a second; a server running at 10 TPS sends one every two seconds.
 * So each update says how many ticks passed (the world age moved on by that
 * much) and how long they took (the time since the last one arrived):
 *
 * <pre>
 * tps = ticks elapsed / seconds elapsed
 * </pre>
 *
 * <p><b>Smoothing</b> is a ratio of sums over the last {@link #DEFAULT_WINDOW}
 * updates &mdash; total ticks over total time &mdash; not an average of each
 * update's rate. The difference matters when the network bunches packets: an
 * update held back 0.9s arrives 1.9s after the one before it, and the next 0.1s
 * after that. Averaged, 10.5 TPS and 200 TPS make 105; summed, it is 40 ticks in
 * 2 seconds, which is the truth.
 *
 * <p><b>Stalls</b> show up before the next update does. If the server freezes,
 * no update arrives to report it, so {@link #getTps} also bounds the estimate by
 * the update that is overdue: three seconds without one means at most twenty
 * ticks in three seconds, whatever the window says.
 *
 * <p>This is the whole algorithm and it needs no bus, no service and no game:
 * call {@link #update} from wherever the time packet is seen. {@link TpsService}
 * is this, wired to {@code PacketEvent}.
 *
 * <p>Thread-safe: updates arrive on the network thread, reads come from the HUD.
 */
public final class TpsTracker {

    /** What a vanilla server runs at. */
    public static final double VANILLA_TPS = 20d;

    /** Updates to smooth over: about ten seconds at full speed. */
    public static final int DEFAULT_WINDOW = 10;

    /** Ticks per update when the describer did not say. What vanilla sends. */
    private static final long DEFAULT_TICKS_PER_UPDATE = 20L;

    /**
     * An age jump bigger than this is a different world, not elapsed time: a
     * minute's worth of ticks in one update. Re-baselines rather than reporting
     * a spike of hundreds of TPS.
     */
    private static final long MAX_TICKS_PER_UPDATE = 20L * 60L;

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final RollingAverage ticks;
    private final RollingAverage seconds;

    private double targetTps = VANILLA_TPS;
    private long lastNanos = Long.MIN_VALUE;
    private long lastAge = -1L;
    private long lastTicks = DEFAULT_TICKS_PER_UPDATE;
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
     * Records a world-clock update.
     *
     * @param nanos when it arrived, on any monotonic clock
     * @param worldAge the world's total age in ticks, or a negative number if
     *        unknown, in which case vanilla's twenty ticks per update is assumed
     */
    public synchronized void update(long nanos, long worldAge) {
        if (lastNanos == Long.MIN_VALUE) {
            baseline(nanos, worldAge);
            return;
        }
        long elapsedTicks = DEFAULT_TICKS_PER_UPDATE;
        if (worldAge >= 0 && lastAge >= 0) {
            elapsedTicks = worldAge - lastAge;
            if (elapsedTicks <= 0 || elapsedTicks > MAX_TICKS_PER_UPDATE) {
                // Went backwards or leapt ahead: a new world, or /time on the age.
                baseline(nanos, worldAge);
                return;
            }
        }
        double elapsedSeconds = (nanos - lastNanos) / NANOS_PER_SECOND;
        if (elapsedSeconds <= 0d) {
            // Two updates in the same instant: keep the ticks, wait for time to pass.
            lastAge = worldAge;
            return;
        }
        ticks.push(elapsedTicks);
        seconds.push(elapsedSeconds);
        lastSample = Math.min(targetTps, elapsedTicks / elapsedSeconds);
        lastTicks = elapsedTicks;
        lastNanos = nanos;
        lastAge = worldAge;
    }

    /**
     * @param nowNanos the current time, on the clock {@link #update} is given
     * @return the smoothed tick rate, bounded by any update now overdue and never
     *         above {@link #getTargetTps()}; the target itself before there is
     *         an estimate, since a server is assumed healthy until shown otherwise
     */
    public synchronized double getTps(long nowNanos) {
        if (!hasEstimateLocked()) {
            return targetTps;
        }
        double tps = Math.min(targetTps, ticks.sum() / seconds.sum());
        double sinceLast = (nowNanos - lastNanos) / NANOS_PER_SECOND;
        double expected = lastTicks / targetTps;
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

    /** The rate the server is meant to run at. 20 unless the server changed it with {@code /tick rate}. */
    public synchronized double getTargetTps() {
        return targetTps;
    }

    /** @param targetTps what the server is meant to run at, for 1.20.3+ servers that change it */
    public synchronized void setTargetTps(double targetTps) {
        Validate.check(targetTps > 0d, "target TPS must be positive");
        this.targetTps = targetTps;
    }

    /** Forgets everything measured. For leaving a server. */
    public synchronized void reset() {
        ticks.clear();
        seconds.clear();
        lastNanos = Long.MIN_VALUE;
        lastAge = -1L;
        lastTicks = DEFAULT_TICKS_PER_UPDATE;
        lastSample = Double.NaN;
    }

    private boolean hasEstimateLocked() {
        return ticks.count() > 0;
    }

    private void baseline(long nanos, long worldAge) {
        ticks.clear();
        seconds.clear();
        lastNanos = nanos;
        lastAge = worldAge;
        lastTicks = DEFAULT_TICKS_PER_UPDATE;
        lastSample = Double.NaN;
    }
}
