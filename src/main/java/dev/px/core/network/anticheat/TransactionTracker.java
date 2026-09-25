package dev.px.core.network.anticheat;

import dev.px.core.util.Validate;

/**
 * Keeps the most recent inbound transactions and reads a
 * {@link TransactionPattern} off them.
 *
 * <p>Pure bookkeeping, with no bus and no game: {@link AntiCheatService} feeds it
 * from {@code PacketEvent}, and anything else can feed it by hand.
 *
 * <p><b>Order</b> is decided by majority, not unanimity: a counter that wraps
 * from {@code Short.MIN_VALUE} back to zero, or a vanilla inventory confirm
 * slipped in between, is one odd step in sixty-four and must not turn a clear
 * countdown into {@link TransactionPattern.Order#IRREGULAR}.
 *
 * <p>Fixed memory: two {@code long} arrays of {@link #DEFAULT_SAMPLE} entries.
 * Thread-safe.
 */
public final class TransactionTracker {

    public static final int DEFAULT_SAMPLE = 64;

    /** At least this share of steps must agree for the ids to count as sequential. */
    private static final double SEQUENTIAL_SHARE = 0.8d;

    /** Fewer ids than this say nothing about order. */
    private static final int MIN_FOR_ORDER = 4;

    private final long[] nanos;
    private final long[] ids;
    private final boolean[] hasId;

    private int cursor;
    private int size;
    private long count;

    public TransactionTracker() {
        this(DEFAULT_SAMPLE);
    }

    public TransactionTracker(int sample) {
        Validate.check(sample >= 2, "sample must hold at least two");
        this.nanos = new long[sample];
        this.ids = new long[sample];
        this.hasId = new boolean[sample];
    }

    /**
     * @param at when it arrived
     * @param id its number, or null when the describer did not give one
     */
    public synchronized void record(long at, Long id) {
        nanos[cursor] = at;
        hasId[cursor] = id != null;
        ids[cursor] = id != null ? id : 0L;
        cursor = (cursor + 1) % nanos.length;
        size = Math.min(size + 1, nanos.length);
        count++;
    }

    public synchronized long getCount() {
        return count;
    }

    public synchronized void reset() {
        cursor = 0;
        size = 0;
        count = 0L;
    }

    public synchronized TransactionPattern pattern() {
        if (size == 0) {
            return TransactionPattern.NONE;
        }
        int oldest = (cursor - size + nanos.length) % nanos.length;
        int newest = (cursor - 1 + nanos.length) % nanos.length;

        double rate = 0d;
        long span = nanos[newest] - nanos[oldest];
        if (size >= 2 && span > 0L) {
            rate = (size - 1) * 1_000_000_000d / span;
        }

        int withIds = 0;
        int up = 0;
        int down = 0;
        int steps = 0;
        boolean anyNegative = false;
        boolean anyPositive = false;
        long lowest = Long.MAX_VALUE;
        long highest = Long.MIN_VALUE;
        boolean havePrevious = false;
        long previous = 0L;

        for (int i = 0; i < size; i++) {
            int at = (oldest + i) % nanos.length;
            if (!hasId[at]) {
                havePrevious = false;
                continue;
            }
            long id = ids[at];
            withIds++;
            lowest = Math.min(lowest, id);
            highest = Math.max(highest, id);
            if (id < 0L) {
                anyNegative = true;
            } else {
                anyPositive = true;
            }
            if (havePrevious) {
                steps++;
                if (id - previous == 1L) {
                    up++;
                } else if (id - previous == -1L) {
                    down++;
                }
            }
            previous = id;
            havePrevious = true;
        }

        if (withIds == 0) {
            return new TransactionPattern(count, size, rate, TransactionPattern.Order.UNKNOWN,
                    TransactionPattern.Sign.UNKNOWN, 0L, 0L);
        }

        TransactionPattern.Order order = TransactionPattern.Order.UNKNOWN;
        if (withIds >= MIN_FOR_ORDER && steps > 0) {
            if (up >= steps * SEQUENTIAL_SHARE) {
                order = TransactionPattern.Order.INCREMENTING;
            } else if (down >= steps * SEQUENTIAL_SHARE) {
                order = TransactionPattern.Order.DECREMENTING;
            } else {
                order = TransactionPattern.Order.IRREGULAR;
            }
        }
        TransactionPattern.Sign sign = anyNegative && anyPositive ? TransactionPattern.Sign.MIXED
                : anyNegative ? TransactionPattern.Sign.NEGATIVE
                : TransactionPattern.Sign.POSITIVE;

        return new TransactionPattern(count, size, rate, order, sign, lowest, highest);
    }
}
