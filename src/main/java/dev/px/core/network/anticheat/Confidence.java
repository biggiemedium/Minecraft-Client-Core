package dev.px.core.network.anticheat;

/** How sure a {@link Detection} is. Later constants are surer. */
public enum Confidence {

    /** Something is there that could be an anticheat, and could be something else. */
    POSSIBLE,

    /** The behaviour matches an anticheat's and little else's. */
    LIKELY,

    /** The server is publicly known to run it, or says so itself. */
    KNOWN;

    public boolean isAtLeast(Confidence other) {
        return compareTo(other) >= 0;
    }
}
