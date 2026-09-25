package dev.px.core.network.anticheat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The signatures {@link AntiCheatService} starts with.
 *
 * <p>Deliberately few. A signature that names a particular anticheat from its
 * transaction numbering is only as good as the last time someone checked that
 * anticheat's source, and a stale one reports the wrong name with confidence,
 * which is worse than reporting none. So what ships here is what does not go
 * stale: the behaviour every latency-compensating anticheat shares, and servers
 * whose anticheat is public knowledge. Register the rest yourself.
 */
public final class AntiCheatSignatures {

    /** The name {@link #transactionBased()} reports, since it cannot say which one it saw. */
    public static final String TRANSACTION_BASED = "Transaction-based anticheat";

    /** Fewer transactions than this are not enough to judge a rate from. */
    private static final int MIN_TRANSACTIONS = 10;

    /** Faster than this is not inventory clicks. */
    private static final double POSSIBLE_RATE = 2d;

    /** About every other tick or faster: a server timing the client. */
    private static final double LIKELY_RATE = 10d;

    private AntiCheatSignatures() {
    }

    /** Every signature below, in the order {@link AntiCheatService} registers them. */
    public static List<AntiCheatSignature> defaults() {
        return Collections.unmodifiableList(Arrays.asList(
                AntiCheatSignature.onServer("hypixel.net", "Watchdog"),
                transactionBased()));
    }

    /**
     * A server sending transactions or pings far faster than inventory clicks
     * would explain.
     *
     * <p>Vanilla never does this. An anticheat that predicts movement does it on
     * every tick, to know which of its own packets the client had received when it
     * moved. This cannot say which anticheat it is &mdash; they all do it &mdash;
     * so it reports {@link #TRANSACTION_BASED}. At equal confidence any named
     * detection ranks above it, so a signature you register for the same evidence
     * is the one {@link AntiCheatService#getPrimary()} reports.
     */
    public static AntiCheatSignature transactionBased() {
        return evidence -> {
            TransactionPattern p = evidence.getTransactions();
            if (p.getCount() < MIN_TRANSACTIONS || p.getRatePerSecond() < POSSIBLE_RATE) {
                return null;
            }
            boolean counter = p.isSequential() && p.getSign() == TransactionPattern.Sign.NEGATIVE;
            Confidence confidence = p.getRatePerSecond() >= LIKELY_RATE || counter
                    ? Confidence.LIKELY
                    : Confidence.POSSIBLE;
            String numbering = p.getOrder() == TransactionPattern.Order.UNKNOWN
                    ? "unnumbered"
                    : p.getOrder().name().toLowerCase(Locale.ROOT) + " " + p.getSign().name().toLowerCase(Locale.ROOT);
            String reason = String.format(Locale.ROOT, "%d transactions at %.1f a second, %s ids",
                    p.getCount(), p.getRatePerSecond(), numbering);
            return Detection.of(TRANSACTION_BASED, confidence, reason);
        };
    }
}
