package dev.px.combat.explosion.rule;

import dev.px.core.math.Box;
import dev.px.core.util.Validate;

/**
 * Where in a target's box an explosion's rays are aimed: the points
 * {@link Exposure#sampled} casts from.
 *
 * <p>How many points, and where, is your game's rule and can change between
 * versions, so it is yours to give. Write one with the lambda form:
 *
 * <pre>{@code
 * SampleGrid mine = (box, sink) -> {
 *     for (...) sink.point(x, y, z);      // your version's sampling
 * };
 * }</pre>
 *
 * <p>{@link #uniform} is plain geometry, for tests and approximations.
 */
@FunctionalInterface
public interface SampleGrid {

    /** Calls {@code sink} once for each sample point of {@code target}. */
    void forEach(Box target, PointSink sink);

    @FunctionalInterface
    interface PointSink {
        void point(double x, double y, double z);
    }

    /**
     * Points spread evenly over the box, both faces included, so
     * {@code uniform(2, 2, 2)} is the eight corners. A count of 1 on an axis is
     * that axis's centre.
     */
    static SampleGrid uniform(int countX, int countY, int countZ) {
        Validate.check(countX > 0 && countY > 0 && countZ > 0, "every count must be positive");
        return (box, sink) -> {
            double minX = box.getMin().getX();
            double minY = box.getMin().getY();
            double minZ = box.getMin().getZ();
            double width = box.getWidth();
            double height = box.getHeight();
            double depth = box.getDepth();
            for (int i = 0; i < countX; i++) {
                double x = minX + width * fraction(i, countX);
                for (int j = 0; j < countY; j++) {
                    double y = minY + height * fraction(j, countY);
                    for (int k = 0; k < countZ; k++) {
                        sink.point(x, y, minZ + depth * fraction(k, countZ));
                    }
                }
            }
        };
    }

    static double fraction(int index, int count) {
        return count == 1 ? 0.5d : (double) index / (count - 1);
    }
}
