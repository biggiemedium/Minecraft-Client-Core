package dev.px.core.layout;

/**
 * A raw drawing callback, given a rectangle to draw into.
 *
 * <p>The escape hatch in {@link Content#custom}. Everything the content
 * system offers is a convenience over this, and anything it cannot express drops
 * to here with no loss of control: the rectangle has already been measured,
 * placed, scaled and clamped, and what happens inside it is entirely yours.
 */
@FunctionalInterface
public interface Draw {

    /**
     * @param x left edge, in screen space
     * @param y top edge
     * @param width the width this part reported when it was measured
     * @param height the height it reported
     */
    void draw(float x, float y, float width, float height);
}
