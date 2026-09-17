package dev.px.core.layout;

/**
 * Where a child sits on its box's <em>cross</em> axis.
 *
 * <p>In a column, that is horizontal: {@code START} is left, {@code END} is
 * right. In a row it is vertical: {@code START} is top. Naming it after the axis
 * rather than after a direction is what lets one value mean the obvious thing in
 * both, so a box can be flipped from a column to a row without every alignment
 * in it becoming wrong.
 */
public enum Align {

    START,
    CENTER,
    END,

    /**
     * Every child is given the box's full cross-axis size.
     *
     * <p>What a GUI row wants for anything spanning its width &mdash; a slider
     * track under a label, a divider. Flexbox calls this {@code align-items:
     * stretch}, and it means the same thing here.
     */
    STRETCH;

    /** @return the offset that places {@code size} within {@code available}. */
    float offsetIn(float available, float size) {
        switch (this) {
            case CENTER: return (available - size) / 2f;
            case END: return available - size;
            default: return 0f;
        }
    }

    /** @return the cross-axis size a child gets: its own, or the box's when stretching. */
    float sizeIn(float available, float size) {
        return this == STRETCH ? available : size;
    }
}
