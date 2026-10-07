package dev.px.core.world;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import lombok.Getter;

/**
 * Where a line first met a block: the point, the cell whose shape it met, the
 * face of that shape it came in through, and how far along the line that was.
 *
 * <pre>{@code
 * RayHit hit = Rays.first(from, to, blocks);
 * if (hit != null) {
 *     land(hit.getPoint(), hit.getFace());
 * }
 * }</pre>
 *
 * <p>Immutable.
 */
@Getter
public final class RayHit {

    /** Where the line met the shape. */
    private final Vec3 point;
    /** The cell whose shape it met. A shape reaching out of its cell can be met outside it. */
    private final Vec3i cell;
    /**
     * The face of the shape's box the line came in through; {@code null} when the
     * line started inside the shape and so never came in through any.
     */
    private final Direction face;
    /** How far along the line, from 0 at its start to 1 at its end. */
    private final double fraction;

    RayHit(Vec3 point, Vec3i cell, Direction face, double fraction) {
        this.point = point;
        this.cell = cell;
        this.face = face;
        this.fraction = fraction;
    }

    @Override
    public String toString() {
        return String.format("RayHit[%s face=%s at %.3f]", cell, face, fraction);
    }
}
