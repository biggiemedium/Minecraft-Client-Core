package dev.px.core.movement.rotation;

/**
 * Conventional priorities for {@link RotationService#request}.
 *
 * <p>The same shape as {@link dev.px.core.event.Priority}, and for the same
 * reason: any int works, and these are spaced so a client can slot its own
 * values between them. Higher wins.
 *
 * <p>A worked ordering, since the numbers only mean something relative to each
 * other: an aim module that must not be interrupted mid-swing takes
 * {@link #HIGHEST}; a block placer that can wait a tick takes {@link #HIGH}; a
 * module that merely prefers to face its target takes {@link #NORMAL}; something
 * cosmetic takes {@link #LOW}.
 *
 * <p>Ties are broken by who acquired the rotation first, not by who asked most
 * recently, so two modules on the same priority will not flicker between each
 * other &mdash; the first to grab it holds until it stops asking. Give them
 * different priorities if the other one should win.
 */
public final class RotationPriority {

    public static final int HIGHEST = 100;
    public static final int HIGH = 75;
    public static final int NORMAL = 50;
    public static final int LOW = 25;
    public static final int LOWEST = 0;

    private RotationPriority() {
    }
}
