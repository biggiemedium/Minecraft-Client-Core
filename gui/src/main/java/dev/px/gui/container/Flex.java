package dev.px.gui.container;

import dev.px.core.layout.Align;
import dev.px.gui.Widget;

import java.util.List;

/**
 * The arithmetic the line containers share: sharing space by grow weight, and
 * placing something along an axis by {@link Align}.
 */
final class Flex {

    private Flex() {
    }

    /**
     * Shares the room along a line between children.
     *
     * <p>Every child starts at its measured size. Room left over goes to the
     * growing children by weight, and room missing is taken from them by
     * weight, never below zero; a child that does not grow keeps its size
     * either way.
     *
     * @param measured each child's measured size along the line
     * @param available the room for the children, gaps already taken out
     * @return each child's size along the line
     */
    static float[] share(List<Widget> children, float[] measured, float available) {
        float[] sizes = measured.clone();
        float weights = 0f;
        float total = 0f;
        for (int i = 0; i < sizes.length; i++) {
            weights += children.get(i).getGrow();
            total += sizes[i];
        }
        float free = available - total;
        if (weights <= 0f || free == 0f) {
            return sizes;
        }
        for (int i = 0; i < sizes.length; i++) {
            float weight = children.get(i).getGrow();
            if (weight > 0f) {
                sizes[i] = Math.max(0f, sizes[i] + free * weight / weights);
            }
        }
        return sizes;
    }

    /** @return the room the gaps between this many children take. */
    static float gaps(int count, float gap) {
        return count > 1 ? gap * (count - 1) : 0f;
    }

    /** @return where something of {@code size} starts within {@code available}. */
    static float offset(Align align, float available, float size) {
        switch (align) {
            case CENTER: return (available - size) / 2f;
            case END: return available - size;
            default: return 0f;
        }
    }
}
