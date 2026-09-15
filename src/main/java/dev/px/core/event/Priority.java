package dev.px.core.event;

/**
 * Handler ordering constants. Higher values run first, so a {@link #HIGHEST}
 * handler can cancel an event before {@link #LOWEST} ever observes it.
 *
 * <p>Any int works; these are conventional anchors, spaced so callers can
 * slot custom priorities between them.
 */
public final class Priority {

    public static final int HIGHEST = 2000;
    public static final int HIGH = 1000;
    public static final int NORMAL = 0;
    public static final int LOW = -1000;
    public static final int LOWEST = -2000;

    private Priority() {
    }
}
