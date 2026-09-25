package dev.px.core.network.anticheat;

import dev.px.core.util.Validate;

import java.util.Locale;

/**
 * One way of recognising an anticheat from what the server has shown.
 *
 * <p>One method: look at the evidence, return a {@link Detection} or null. The
 * factories below cover the evidence that names an anticheat outright; anything
 * cleverer &mdash; a transaction numbering you have seen one use, a channel its
 * plugin registers &mdash; is a lambda:
 *
 * <pre>{@code
 * Core.anticheat().register(AntiCheatSignature.onServer("example.net", "Vulcan"));
 *
 * Core.anticheat().register(evidence -> {
 *     TransactionPattern p = evidence.getTransactions();
 *     return p.getOrder() == TransactionPattern.Order.DECREMENTING && p.getHighestId() < -1000
 *             ? Detection.of("MyAntiCheat", Confidence.LIKELY, "counts down from -1000")
 *             : null;
 * });
 * }</pre>
 *
 * <p>Evaluated on the game thread, about once a second and whenever the server
 * changes. A signature that throws is logged once and skipped.
 */
@FunctionalInterface
public interface AntiCheatSignature {

    /** @return what this signature recognises, or null if it recognises nothing */
    Detection detect(ServerEvidence evidence);

    /**
     * A server known to run a particular anticheat.
     *
     * @param domain matched with {@link dev.px.core.network.server.ServerInfo#isOn}, so
     *        subdomains count
     */
    static AntiCheatSignature onServer(String domain, String anticheat) {
        Validate.notNull(domain, "domain");
        Validate.notNull(anticheat, "anticheat");
        String reason = "the server is on " + domain;
        return evidence -> evidence.getServer().isOn(domain)
                ? Detection.of(anticheat, Confidence.KNOWN, reason)
                : null;
    }

    /** A server whose brand contains {@code text}, ignoring case. */
    static AntiCheatSignature brandContains(String text, String anticheat, Confidence confidence) {
        Validate.notNull(text, "text");
        Validate.notNull(anticheat, "anticheat");
        Validate.notNull(confidence, "confidence");
        String lower = text.toLowerCase(Locale.ROOT);
        String reason = "the server brand contains \"" + text + "\"";
        return evidence -> evidence.getRawBrand().toLowerCase(Locale.ROOT).contains(lower)
                ? Detection.of(anticheat, confidence, reason)
                : null;
    }

    /** A server that registered the plugin channel {@code channel}. */
    static AntiCheatSignature channel(String channel, String anticheat, Confidence confidence) {
        Validate.notNull(channel, "channel");
        Validate.notNull(anticheat, "anticheat");
        Validate.notNull(confidence, "confidence");
        String reason = "the server registered the channel " + channel;
        return evidence -> evidence.getServer().hasChannel(channel)
                ? Detection.of(anticheat, confidence, reason)
                : null;
    }
}
