package dev.px.core.util.spatial;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.math.RotationMath;

import java.util.function.Predicate;

/**
 * Walks the exact sequence of grid cells a ray passes through.
 *
 * <p>Core cannot raytrace the world &mdash; it has no world &mdash; but the hard
 * half of a raytrace is not the world lookup, it is deciding which cells the ray
 * crosses and in what order. That part is arithmetic, so it lives here and the
 * caller supplies a predicate saying which cells stop it.
 *
 * <p>The naive version of this samples the ray every 0.1 blocks and rounds. It is
 * wrong in both directions at once: it misses a cell the ray clips the corner of,
 * and it tests the same cell a dozen times. Neither shows up in casual testing
 * and both show up as a block-place that silently does nothing.
 *
 * <p>This is the Amanatides&ndash;Woo traversal instead: step to whichever axis
 * boundary comes next, so every cell the ray truly enters is visited exactly once,
 * in order, with no sampling interval to tune. Cost is proportional to the number
 * of cells crossed.
 *
 * <p><b>Complexity.</b> O(c) in the cells crossed, each step being a constant
 * three comparisons and one addition. For a ray of length d, c is at most
 * {@code |dx|+|dy|+|dz| + 1 ≈ √3·d} cells.
 *
 * <pre>
 * tDelta_a = 1 / |dir_a|                       cost of crossing one cell on axis a
 * tMax_a   = (nextBoundary_a - origin_a) / dir_a
 *
 * each step: advance the axis with the smallest tMax, then tMax_a += tDelta_a
 * </pre>
 *
 * <p>The face reported is the one the ray entered through, which is what a block
 * placement needs &mdash; you place against the side you are looking at.
 *
 * <pre>{@code
 * VoxelRay.Hit hit = VoxelRay.trace(eyePosition, rotation, 4.5d,
 *         cell -> world.isSolid(cell));
 * if (hit != null) {
 *     place(hit.getPosition().offset(hit.getFace()));
 * }
 * }</pre>
 */
public final class VoxelRay {

    private VoxelRay() {
    }

    /** What a visitor is told about each cell the ray enters. */
    public interface Visitor {

        /** @return {@code true} to keep going, {@code false} to stop the traversal */
        boolean visit(Vec3i position, Direction face, double distance);
    }

    /** Where a ray stopped. */
    public static final class Hit {

        private final Vec3i position;
        private final Direction face;
        private final double distance;
        private final Vec3 origin;
        private final Vec3 direction;

        private Hit(Vec3i position, Direction face, double distance, Vec3 origin, Vec3 direction) {
            this.position = position;
            this.face = face;
            this.distance = distance;
            this.origin = origin;
            this.direction = direction;
        }

        /** @return the cell that stopped the ray. */
        public Vec3i getPosition() {
            return position;
        }

        /**
         * @return the face the ray entered through, or {@code null} when the ray
         *         started inside the cell that stopped it &mdash; there is no face
         *         in that case, and reporting one would place a block inside the
         *         player
         */
        public Direction getFace() {
            return face;
        }

        /** @return how far along the ray the cell was entered, in blocks. */
        public double getDistance() {
            return distance;
        }

        /** @return the exact point on the cell's surface the ray crossed. */
        public Vec3 getPoint() {
            return origin.add(direction.scale(distance));
        }

        /** @return the cell against the struck face: where a block would go. */
        public Vec3i getAdjacent() {
            return face == null ? position : position.offset(face);
        }

        @Override
        public String toString() {
            return String.format("Hit[%s face=%s at %.2f]", position, face, distance);
        }
    }

    // --------------------------------------------------------------- tracing

    /**
     * @param direction which way to look. Need not be normalised; it is treated as
     *                  a direction, and distances come back in blocks either way
     * @return the first cell {@code solid} accepts, or {@code null} if none was
     *         found within {@code maxDistance}
     */
    public static Hit trace(Vec3 origin, Vec3 direction, double maxDistance, Predicate<Vec3i> solid) {
        Vec3 unit = direction.normalize();
        if (unit.equals(Vec3.ZERO)) {
            return null;
        }
        Hit[] result = new Hit[1];
        forEach(origin, unit, maxDistance, (position, face, distance) -> {
            if (!solid.test(position)) {
                return true;
            }
            result[0] = new Hit(position, face, distance, origin, unit);
            return false;
        });
        return result[0];
    }

    /** Traces along a yaw/pitch rotation rather than a vector. */
    public static Hit trace(Vec3 origin, Vec2 rotation, double maxDistance, Predicate<Vec3i> solid) {
        return trace(origin, RotationMath.direction(rotation), maxDistance, solid);
    }

    /**
     * Visits every cell the ray crosses, in order, starting with the one the ray
     * begins inside.
     *
     * <p>The lower-level form, for the traversals that are not looking for a first
     * hit: gathering every cell along a line, drawing a debug trace, or stopping on
     * a condition a predicate cannot express.
     */
    public static void forEach(Vec3 origin, Vec3 direction, double maxDistance, Visitor visitor) {
        Vec3 unit = direction.normalize();
        if (unit.equals(Vec3.ZERO) || maxDistance < 0d) {
            return;
        }

        int x = floor(origin.getX());
        int y = floor(origin.getY());
        int z = floor(origin.getZ());

        int stepX = signum(unit.getX());
        int stepY = signum(unit.getY());
        int stepZ = signum(unit.getZ());

        // Distance along the ray to the first boundary on each axis, and the
        // distance between consecutive boundaries. An axis the ray does not move
        // along never comes up, which infinity expresses without a special case.
        double maxX = boundary(origin.getX(), unit.getX(), x, stepX);
        double maxY = boundary(origin.getY(), unit.getY(), y, stepY);
        double maxZ = boundary(origin.getZ(), unit.getZ(), z, stepZ);

        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1d / unit.getX());
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1d / unit.getY());
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1d / unit.getZ());

        // The cell the ray starts in was not entered through any face.
        if (!visitor.visit(Vec3i.of(x, y, z), null, 0d)) {
            return;
        }

        while (true) {
            double distance;
            Direction face;
            if (maxX < maxY && maxX < maxZ) {
                distance = maxX;
                maxX += deltaX;
                x += stepX;
                face = stepX > 0 ? Direction.WEST : Direction.EAST;
            } else if (maxY < maxZ) {
                distance = maxY;
                maxY += deltaY;
                y += stepY;
                face = stepY > 0 ? Direction.DOWN : Direction.UP;
            } else {
                distance = maxZ;
                maxZ += deltaZ;
                z += stepZ;
                face = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
            }
            if (distance > maxDistance || Double.isInfinite(distance)) {
                return;
            }
            if (!visitor.visit(Vec3i.of(x, y, z), face, distance)) {
                return;
            }
        }
    }

    /** Traces along a rotation, visiting every cell. */
    public static void forEach(Vec3 origin, Vec2 rotation, double maxDistance, Visitor visitor) {
        forEach(origin, RotationMath.direction(rotation), maxDistance, visitor);
    }

    // ------------------------------------------------------------- internals

    /** @return the distance along the ray to the first cell boundary on this axis. */
    private static double boundary(double origin, double direction, int cell, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double edge = step > 0 ? cell + 1 : cell;
        return (edge - origin) / direction;
    }

    private static int signum(double value) {
        return value > 0d ? 1 : value < 0d ? -1 : 0;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
