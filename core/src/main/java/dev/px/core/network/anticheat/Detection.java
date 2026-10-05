package dev.px.core.network.anticheat;

import dev.px.core.util.Validate;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * An anticheat a {@link AntiCheatSignature} thinks the server is running, and why.
 *
 * <p>The reason is for people: it is what a debug overlay or a log line shows, so
 * a wrong guess can be traced back to the evidence that produced it.
 */
@Getter
@EqualsAndHashCode
public final class Detection {

    private final String name;
    private final Confidence confidence;
    private final String reason;

    private Detection(String name, Confidence confidence, String reason) {
        this.name = name;
        this.confidence = confidence;
        this.reason = reason;
    }

    public static Detection of(String name, Confidence confidence, String reason) {
        Validate.notNull(name, "name");
        Validate.notNull(confidence, "confidence");
        Validate.notNull(reason, "reason");
        return new Detection(name, confidence, reason);
    }

    /** @return whether this names {@code anticheat}, ignoring case */
    public boolean is(String anticheat) {
        return name.equalsIgnoreCase(anticheat);
    }

    @Override
    public String toString() {
        return name + " (" + confidence + ": " + reason + ")";
    }
}
