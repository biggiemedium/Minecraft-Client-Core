package dev.px.core.hud;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * An alignment line an element is currently snapped to.
 *
 * <p>Pure geometry: a screen coordinate and which way the line runs. Core works
 * out that a snap happened and where; drawing the line &mdash; colour, dash,
 * thickness, or not at all &mdash; belongs to whoever is presenting the editor.
 *
 * <pre>{@code
 * for (SnapGuide guide : view.getGuides()) {
 *     if (guide.isVertical()) {
 *         Render.line(guide.getPosition(), 0f, guide.getPosition(), screenHeight, 1f, accent);
 *     } else {
 *         Render.line(0f, guide.getPosition(), screenWidth, guide.getPosition(), 1f, accent);
 *     }
 * }
 * }</pre>
 */
@Getter
@RequiredArgsConstructor
public final class SnapGuide {

    /** Screen coordinate the line sits at: an x for a vertical line, a y for a horizontal one. */
    private final float position;

    /**
     * Whether the line runs top to bottom.
     *
     * <p>A guide on the horizontal axis constrains an element's x and therefore
     * draws as a <em>vertical</em> line. The old field was named after the axis
     * and read as the opposite of what it drew, so this one is named after the
     * line.
     */
    private final boolean vertical;

    @Override
    public String toString() {
        return (vertical ? "SnapGuide[x=" : "SnapGuide[y=") + position + "]";
    }
}
