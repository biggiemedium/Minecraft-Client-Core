package dev.px.core.util.time;

import dev.px.core.util.Validate;

import java.util.Arrays;

/**
 * How many times something happened per second, over a sliding window.
 *
 * <p>Packets per second, clicks per second, chunk loads per second: all the same
 * question. Keeping every timestamp answers it exactly and allocates on every
 * event. This splits the window into a fixed number of buckets instead, so
 * {@link #record} is a counter increment and the answer is exact to within one
 * bucket's width.
 *
 * <pre>{@code
 * RateMeter clicks = RateMeter.perSecond();
 * clicks.record(System.nanoTime());
 * int cps = (int) clicks.rate(System.nanoTime());
 * }</pre>
 *
 * <p>Time is passed in rather than read, so the meter works on any clock and a
 * test can drive it by hand. Timestamps must not run backwards.
 *
 * <p><b>Complexity.</b> {@link #record} is O(1) amortised; {@link #rate} is
 * O(buckets). Nothing allocates after construction.
 *
 * <p>Not thread-safe.
 */
public final class RateMeter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long bucketNanos;
    private final long[] counts;

    /** Index of the bucket that {@link #currentBucket} names. */
    private int cursor;

    /** Absolute bucket number (time / width) the cursor holds; -1 before the first event. */
    private long currentBucket = -1;

    private RateMeter(long windowNanos, int buckets) {
        Validate.check(windowNanos > 0, "window must be positive");
        Validate.check(buckets > 0, "buckets must be positive");
        Validate.check(windowNanos >= buckets, "window must be at least one nanosecond per bucket");
        this.bucketNanos = windowNanos / buckets;
        this.counts = new long[buckets];
    }

    /** Events per second over the last second, to a twentieth of a second. */
    public static RateMeter perSecond() {
        return new RateMeter(NANOS_PER_SECOND, 20);
    }

    /**
     * @param windowMillis how far back to count
     * @param buckets how finely to slice the window; more is more exact and slower to read
     */
    public static RateMeter over(long windowMillis, int buckets) {
        return new RateMeter(windowMillis * 1_000_000L, buckets);
    }

    /** Counts one event at {@code nanos}. */
    public void record(long nanos) {
        record(nanos, 1);
    }

    /** Counts {@code amount} events at {@code nanos}. */
    public void record(long nanos, long amount) {
        advanceTo(nanos);
        counts[cursor] += amount;
    }

    /** @return events per second across the window ending at {@code nanos} */
    public double rate(long nanos) {
        return count(nanos) * (double) NANOS_PER_SECOND / (bucketNanos * counts.length);
    }

    /** @return how many events fell inside the window ending at {@code nanos} */
    public long count(long nanos) {
        advanceTo(nanos);
        long total = 0;
        for (long count : counts) {
            total += count;
        }
        return total;
    }

    public void clear() {
        Arrays.fill(counts, 0L);
        cursor = 0;
        currentBucket = -1;
    }

    private void advanceTo(long nanos) {
        long bucket = Math.floorDiv(nanos, bucketNanos);
        if (currentBucket < 0) {
            currentBucket = bucket;
            return;
        }
        long steps = bucket - currentBucket;
        if (steps <= 0) {
            return;
        }
        if (steps >= counts.length) {
            Arrays.fill(counts, 0L);
            cursor = (int) Math.floorMod(bucket, (long) counts.length);
        } else {
            for (long i = 0; i < steps; i++) {
                cursor = (cursor + 1) % counts.length;
                counts[cursor] = 0L;
            }
        }
        currentBucket = bucket;
    }

    @Override
    public String toString() {
        return "RateMeter(" + counts.length + " x " + (bucketNanos / 1_000_000L) + "ms)";
    }
}
