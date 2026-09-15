package dev.px.core.hud;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * An immutable width and height, with no position.
 *
 * <p>What an element reports from {@link HudElement#getPreferredSize()}. It is
 * expected to change every frame &mdash; a clock is wider at 12:00 than at 1:00,
 * an ArrayList grows as modules are enabled &mdash; so nothing caches it.
 */
@Getter
@EqualsAndHashCode
public final class Size {

    public static final Size ZERO = new Size(0f, 0f);

    private final float width;
    private final float height;

    private Size(float width, float height) {
        this.width = width;
        this.height = height;
    }

    /** Negative dimensions are clamped to zero, so no element can occupy inverted space. */
    public static Size of(float width, float height) {
        return new Size(Math.max(0f, width), Math.max(0f, height));
    }

    /** Grows by {@code padding} on all four sides, i.e. twice that in each dimension. */
    public Size padded(float padding) {
        return of(width + padding * 2f, height + padding * 2f);
    }

    public Size scaled(float factor) {
        return of(width * factor, height * factor);
    }

    /** @return the larger of each dimension. Useful when sizing to fit several children. */
    public Size union(Size other) {
        return of(Math.max(width, other.width), Math.max(height, other.height));
    }

    public boolean isEmpty() {
        return width <= 0f || height <= 0f;
    }

    @Override
    public String toString() {
        return String.format("%.1fx%.1f", width, height);
    }
}
