package dev.px.core.network.anticheat;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Locale;

/**
 * What the server's transactions (1.8&ndash;1.16) or pings (1.17+) look like.
 *
 * <p>A vanilla server sends transactions only to confirm inventory clicks, with
 * small positive numbers, and never sends a ping of its own accord. An anticheat
 * that compensates for latency sends one every tick and watches for the reply,
 * which is what makes this worth measuring: the rate alone separates the two, and
 * the numbering &mdash; which way it counts, and on which side of zero &mdash;
 * is what a signature for a particular anticheat can match on.
 *
 * <p>Built by {@link TransactionTracker}; immutable.
 */
@Getter
@EqualsAndHashCode
public final class TransactionPattern {

    /** Nothing seen. */
    public static final TransactionPattern NONE =
            new TransactionPattern(0, 0, 0d, Order.UNKNOWN, Sign.UNKNOWN, 0L, 0L);

    /** Transactions since the tracker was last reset. */
    private final long count;

    /** How many of the most recent ones the rest of this is computed from. */
    private final int sampled;

    /** Arrivals per second across the sample. */
    private final double ratePerSecond;

    /** Which way consecutive ids count. */
    private final Order order;

    /** Which side of zero the ids fall. */
    private final Sign sign;

    /** Lowest id in the sample, or 0 when ids are unknown. */
    private final long lowestId;

    /** Highest id in the sample, or 0 when ids are unknown. */
    private final long highestId;

    TransactionPattern(long count, int sampled, double ratePerSecond, Order order, Sign sign,
                       long lowestId, long highestId) {
        this.count = count;
        this.sampled = sampled;
        this.ratePerSecond = ratePerSecond;
        this.order = order;
        this.sign = sign;
        this.lowestId = lowestId;
        this.highestId = highestId;
    }

    /** @return whether ids go up or down by one, the mark of a counter rather than inventory clicks */
    public boolean isSequential() {
        return order == Order.INCREMENTING || order == Order.DECREMENTING;
    }

    @Override
    public String toString() {
        if (count == 0) {
            return "TransactionPattern(none)";
        }
        return String.format(Locale.ROOT, "TransactionPattern(%d at %.1f/s, %s, %s, %d..%d)",
                count, ratePerSecond, order, sign, lowestId, highestId);
    }

    public enum Order {
        /** Each id is one more than the last. */
        INCREMENTING,
        /** Each id is one less than the last. */
        DECREMENTING,
        /** Neither: random, or a counter with gaps. */
        IRREGULAR,
        /** Too few ids to say, or the describer gave none. */
        UNKNOWN
    }

    public enum Sign {
        /** Every id is zero or more. Vanilla's inventory transactions are. */
        POSITIVE,
        /** Every id is below zero, which keeps them clear of vanilla's own. */
        NEGATIVE,
        MIXED,
        UNKNOWN
    }
}
