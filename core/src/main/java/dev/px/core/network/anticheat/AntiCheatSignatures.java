package dev.px.core.network.anticheat;

import dev.px.core.util.Validate;

import java.util.Locale;

/**
 * Ready-made signatures that judge behaviour rather than names.
 *
 * <p>{@link AntiCheatService} starts with none registered. A signature that names
 * a particular anticheat, or a particular server's, is a fact about one game's
 * ecosystem and goes stale; a stale one reports the wrong name with confidence,
 * which is worse than reporting none. So those are yours to write, and the
 * numbers below are yours to choose.
 */
public final class AntiCheatSignatures {

    /** The name {@link #transactionBased} reports, since it cannot say which one it saw. */
    public static final String TRANSACTION_BASED = "Transaction-based anticheat";

    /** Fewer transactions than this are not enough to judge a rate from. A statistical floor, not a game fact. */
    private static final int MIN_TRANSACTIONS = 10;

    private AntiCheatSignatures() {
    }

    /**
     * A server sending packets for the client to answer far faster than the
     * game itself would.
     *
     * <p>An anticheat that predicts movement sends one on a fixed short period, to
     * know which of its own packets the client had received when it moved. This
     * cannot say which anticheat it is &mdash; they all do it &mdash; so it reports
     * {@link #TRANSACTION_BASED}. At equal confidence any named detection ranks
     * above it, so a signature you register for the same evidence is the one
     * {@link AntiCheatService#getPrimary()} reports.
     *
     * <pre>{@code
     * // e.g. on a game whose own transactions only follow inventory clicks
     * Core.anticheat().register(AntiCheatSignatures.transactionBased(2, 10));
     * }</pre>
     *
     * @param possibleRate transactions a second above which the game's own traffic
     *        no longer explains them: {@link Confidence#POSSIBLE}
     * @param likelyRate transactions a second above which it is a server timing the
     *        client: {@link Confidence#LIKELY}
     */
    public static AntiCheatSignature transactionBased(double possibleRate, double likelyRate) {
        Validate.check(possibleRate > 0d, "possibleRate must be positive");
        Validate.check(likelyRate >= possibleRate, "likelyRate must not be below possibleRate");
        return evidence -> {
            TransactionPattern p = evidence.getTransactions();
            if (p.getCount() < MIN_TRANSACTIONS || p.getRatePerSecond() < possibleRate) {
                return null;
            }
            Confidence confidence = p.getRatePerSecond() >= likelyRate ? Confidence.LIKELY : Confidence.POSSIBLE;
            String numbering = p.getOrder() == TransactionPattern.Order.UNKNOWN
                    ? "unnumbered"
                    : p.getOrder().name().toLowerCase(Locale.ROOT) + " " + p.getSign().name().toLowerCase(Locale.ROOT);
            String reason = String.format(Locale.ROOT, "%d transactions at %.1f a second, %s ids",
                    p.getCount(), p.getRatePerSecond(), numbering);
            return Detection.of(TRANSACTION_BASED, confidence, reason);
        };
    }
}
