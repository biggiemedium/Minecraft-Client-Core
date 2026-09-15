package dev.px.core.hud;

import lombok.Getter;

import java.util.Locale;

/**
 * The screen reference point an element is positioned relative to.
 *
 * <p>This enum is where most of the HUD system's behaviour actually lives.
 * Storing position as an anchor plus an offset, rather than as absolute screen
 * coordinates, buys three things for free:
 *
 * <ul>
 *   <li><b>Resolution independence.</b> A bottom-right element stays in the
 *       bottom-right corner when the window is resized, instead of being flung
 *       off-screen or stranded in the middle.</li>
 *   <li><b>Correct growth direction.</b> The {@code - a * size} term means a
 *       right-anchored element grows leftwards, a bottom-anchored one grows
 *       upwards, and a centred one grows both ways. This falls out of the
 *       formula, so no code anywhere special-cases growth.</li>
 *   <li><b>Stable edge gaps.</b> An offset means "distance from my anchor",
 *       which is what a user setting up a HUD actually intends.</li>
 * </ul>
 *
 * <p>The factors are always 0, 0.5 or 1, for the near edge, the centre, and the
 * far edge of the axis.
 */
@Getter
public enum Anchor {

    TOP_LEFT(0f, 0f),
    TOP_CENTER(0.5f, 0f),
    TOP_RIGHT(1f, 0f),

    MIDDLE_LEFT(0f, 0.5f),
    CENTER(0.5f, 0.5f),
    MIDDLE_RIGHT(1f, 0.5f),

    BOTTOM_LEFT(0f, 1f),
    BOTTOM_CENTER(0.5f, 1f),
    BOTTOM_RIGHT(1f, 1f);

    /** Horizontal factor: 0 left, 0.5 centre, 1 right. */
    private final float ax;

    /** Vertical factor: 0 top, 0.5 middle, 1 bottom. */
    private final float ay;

    Anchor(float ax, float ay) {
        this.ax = ax;
        this.ay = ay;
    }

    /**
     * Resolves an offset to an absolute screen x.
     *
     * <pre>x = ax * screenWidth + offsetX - ax * width</pre>
     */
    public float resolveX(float screenWidth, float offsetX, float width) {
        return ax * screenWidth + offsetX - ax * width;
    }

    public float resolveY(float screenHeight, float offsetY, float height) {
        return ay * screenHeight + offsetY - ay * height;
    }

    /**
     * The inverse of {@link #resolveX}: the offset that would place an element of
     * {@code width} at absolute {@code x}.
     *
     * <p>Needed whenever something works in absolute coordinates and has to be
     * written back as layout state, which is every drag and every anchor change.
     */
    public float offsetXFor(float screenWidth, float x, float width) {
        return x - ax * screenWidth + ax * width;
    }

    public float offsetYFor(float screenHeight, float y, float height) {
        return y - ay * screenHeight + ay * height;
    }

    public boolean isLeft() {
        return ax == 0f;
    }

    public boolean isRight() {
        return ax == 1f;
    }

    public boolean isTop() {
        return ay == 0f;
    }

    public boolean isBottom() {
        return ay == 1f;
    }

    /** Parses a persisted name, falling back to {@link #TOP_LEFT}. */
    public static Anchor byName(String name) {
        if (name == null) {
            return TOP_LEFT;
        }
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return TOP_LEFT;
        }
    }

    /**
     * @return the anchor whose reference point is closest to the given position,
     *         as a fraction of the screen. Lets an editor re-anchor an element to
     *         the corner it was dropped nearest, so it behaves sensibly on the
     *         next resolution change.
     */
    public static Anchor nearest(float centerX, float centerY, float screenWidth, float screenHeight) {
        float fx = screenWidth <= 0f ? 0f : centerX / screenWidth;
        float fy = screenHeight <= 0f ? 0f : centerY / screenHeight;
        float ax = fx < 0.33f ? 0f : fx > 0.67f ? 1f : 0.5f;
        float ay = fy < 0.33f ? 0f : fy > 0.67f ? 1f : 0.5f;
        for (Anchor anchor : values()) {
            if (anchor.ax == ax && anchor.ay == ay) {
                return anchor;
            }
        }
        return TOP_LEFT;
    }
}
