package dev.px.core.layout;

import dev.px.core.render.Color;
import dev.px.core.render.Render;

import java.util.ArrayList;
import java.util.List;

/**
 * Several shapes treated as one region: what an element made of separate panels
 * occupies.
 *
 * <p>An ArrayList is the case this exists for. Each row is its own rounded
 * panel, of its own width, and the element as a whole is the rows &mdash; not
 * the rectangle around them. A bounding box would claim the empty space beside
 * the short rows, so clicking well clear of {@code "ESP"} would still select the
 * list, and the editor would outline a box the element does not fill.
 *
 * <p>{@link #stroke} draws the <b>silhouette</b>: the outside of the combined
 * region, with the shared edges between touching parts left out. Outlining each
 * part separately would draw a line between every adjacent row, which is the
 * exact opposite of what an outline is for.
 *
 * <p>How the inner edges go: an edge of one part is hidden wherever another part
 * has material on the far side of it. A row's bottom edge disappears where the
 * row below it starts, because the region continues there; the bottom edge of
 * the <em>last</em> row survives, because nothing is below it. Each of the four
 * edges asks that question in its own direction, which is why two rows touching
 * along a shared line lose it from both sides at once and a corner where two
 * outer edges merely meet keeps both.
 *
 * <p>Parts are treated as their bounding rectangles here. A single rounded panel
 * never reaches this class &mdash; one shape is returned as itself, corner radius
 * intact &mdash; so rounding is only approximated when several rounded panels are
 * genuinely merged into one outline.
 */
final class UnionShape implements Shape {

    /** Float slack, so an edge that should land exactly on another is treated as touching. */
    private static final float EPSILON = 0.001f;

    private final Shape[] parts;

    UnionShape(Shape... parts) {
        if (parts.length == 0) {
            throw new IllegalArgumentException("A union needs at least one shape");
        }
        this.parts = parts.clone();
    }

    @Override
    public boolean contains(float x, float y) {
        for (Shape part : parts) {
            if (part.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Bounds bounds() {
        Bounds enclosing = parts[0].bounds();
        float minX = enclosing.getX();
        float minY = enclosing.getY();
        float maxX = enclosing.getRight();
        float maxY = enclosing.getBottom();

        for (int i = 1; i < parts.length; i++) {
            Bounds part = parts[i].bounds();
            minX = Math.min(minX, part.getX());
            minY = Math.min(minY, part.getY());
            maxX = Math.max(maxX, part.getRight());
            maxY = Math.max(maxY, part.getBottom());
        }
        return Bounds.of(minX, minY, maxX - minX, maxY - minY);
    }

    @Override
    public void stroke(float thickness, Color color) {
        Bounds[] rects = new Bounds[parts.length];
        for (int i = 0; i < parts.length; i++) {
            rects[i] = parts[i].bounds();
        }

        for (int i = 0; i < rects.length; i++) {
            Bounds rect = rects[i];
            // Each edge is hidden where another part continues the region past it:
            // above a top edge, below a bottom edge, and so on outwards.
            horizontal(rects, i, rect.getX(), rect.getRight(), rect.getY(), true, thickness, color);
            horizontal(rects, i, rect.getX(), rect.getRight(), rect.getBottom(), false, thickness, color);
            vertical(rects, i, rect.getY(), rect.getBottom(), rect.getX(), true, thickness, color);
            vertical(rects, i, rect.getY(), rect.getBottom(), rect.getRight(), false, thickness, color);
        }
    }

    /**
     * @param above whether the region this edge belongs to lies below it, so the
     *              edge is hidden by a part covering it from above
     */
    private void horizontal(Bounds[] rects, int owner, float from, float to, float y,
                            boolean above, float thickness, Color color) {
        List<float[]> visible = new ArrayList<>(2);
        visible.add(new float[] { from, to });

        for (int i = 0; i < rects.length; i++) {
            if (i == owner || !covers(rects[i].getY(), rects[i].getBottom(), y, above)) {
                continue;
            }
            visible = subtract(visible, rects[i].getX(), rects[i].getRight());
        }
        for (float[] segment : visible) {
            Render.line(segment[0], y, segment[1], y, thickness, color);
        }
    }

    private void vertical(Bounds[] rects, int owner, float from, float to, float x,
                          boolean left, float thickness, Color color) {
        List<float[]> visible = new ArrayList<>(2);
        visible.add(new float[] { from, to });

        for (int i = 0; i < rects.length; i++) {
            if (i == owner || !covers(rects[i].getX(), rects[i].getRight(), x, left)) {
                continue;
            }
            visible = subtract(visible, rects[i].getY(), rects[i].getBottom());
        }
        for (float[] segment : visible) {
            Render.line(x, segment[0], x, segment[1], thickness, color);
        }
    }

    /**
     * Whether a part spanning {@code start..end} has material on the far side of
     * an edge at {@code line}.
     *
     * <p>The interval is half-open, and which end is open is what separates an
     * inner edge from an outer one. Two rows that share a line: the upper row's
     * bottom edge is covered from below, the lower row's top edge is covered from
     * above, and both vanish. Two rows that merely start at the same height keep
     * their top edges, because neither has anything above it.
     */
    private static boolean covers(float start, float end, float line, boolean fromBefore) {
        return fromBefore
                ? line > start + EPSILON && line <= end + EPSILON
                : line >= start - EPSILON && line < end - EPSILON;
    }

    /** Removes {@code from..to} from every segment, splitting any it lands inside. */
    private static List<float[]> subtract(List<float[]> segments, float from, float to) {
        List<float[]> remaining = new ArrayList<>(segments.size() + 1);
        for (float[] segment : segments) {
            if (to <= segment[0] + EPSILON || from >= segment[1] - EPSILON) {
                remaining.add(segment);
                continue;
            }
            if (from > segment[0] + EPSILON) {
                remaining.add(new float[] { segment[0], from });
            }
            if (to < segment[1] - EPSILON) {
                remaining.add(new float[] { to, segment[1] });
            }
        }
        return remaining;
    }

    @Override
    public Shape scaled(float originX, float originY, float factor) {
        Shape[] moved = new Shape[parts.length];
        for (int i = 0; i < parts.length; i++) {
            moved[i] = parts[i].scaled(originX, originY, factor);
        }
        return new UnionShape(moved);
    }
}
