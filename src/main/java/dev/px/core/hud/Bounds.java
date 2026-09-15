package dev.px.core.hud;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * An immutable axis-aligned rectangle in scaled screen space.
 *
 * <p>The HUD's 2D counterpart to {@link dev.px.core.math.Box}, which is
 * world-space and three-dimensional. They are kept apart on purpose: a screen
 * rectangle and a world box share no operations worth unifying, and one type
 * doing both would invite passing the wrong one.
 *
 * <p>This is the <em>layout</em> geometry: where an element sits and how much
 * room it occupies. What it actually draws, and where it responds to clicks,
 * are the element's {@link Shape} and need not match.
 */
@Getter
@EqualsAndHashCode
public final class Bounds {

    public static final Bounds EMPTY = new Bounds(0f, 0f, 0f, 0f);

    private final float x;
    private final float y;
    private final float width;
    private final float height;

    private Bounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static Bounds of(float x, float y, float width, float height) {
        return new Bounds(x, y, Math.max(0f, width), Math.max(0f, height));
    }

    public static Bounds of(float x, float y, Size size) {
        return new Bounds(x, y, size.getWidth(), size.getHeight());
    }

    /** Builds bounds from two opposite corners, in any order. */
    public static Bounds between(float x1, float y1, float x2, float y2) {
        return new Bounds(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    public float getRight() {
        return x + width;
    }

    public float getBottom() {
        return y + height;
    }

    public float getCenterX() {
        return x + width / 2f;
    }

    public float getCenterY() {
        return y + height / 2f;
    }

    public Size getSize() {
        return Size.of(width, height);
    }

    public boolean contains(float px, float py) {
        return px >= x && px <= getRight() && py >= y && py <= getBottom();
    }

    public boolean intersects(Bounds other) {
        return getRight() > other.x && x < other.getRight()
                && getBottom() > other.y && y < other.getBottom();
    }

    public Bounds translate(float dx, float dy) {
        return new Bounds(x + dx, y + dy, width, height);
    }

    public Bounds at(float newX, float newY) {
        return new Bounds(newX, newY, width, height);
    }

    /** Grows by {@code amount} on every side. Negative shrinks. */
    public Bounds expand(float amount) {
        return of(x - amount, y - amount, width + amount * 2f, height + amount * 2f);
    }

    /**
     * Scales the rectangle about an arbitrary origin.
     *
     * <p>Used to convert an element's natural-size geometry into screen space
     * once its scale factor is applied, so elements never deal with scale.
     */
    public Bounds scaled(float originX, float originY, float factor) {
        return new Bounds(
                originX + (x - originX) * factor,
                originY + (y - originY) * factor,
                width * factor,
                height * factor);
    }

    @Override
    public String toString() {
        return String.format("Bounds[%.1f, %.1f, %.1fx%.1f]", x, y, width, height);
    }
}
