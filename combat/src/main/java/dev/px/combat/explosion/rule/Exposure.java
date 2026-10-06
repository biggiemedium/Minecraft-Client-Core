package dev.px.combat.explosion.rule;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;
import dev.px.core.world.Rays;

/**
 * How much of a target an explosion reaches: 0 when every path is blocked, 1
 * when none is.
 *
 * <pre>{@code
 * Exposure.sampled(mySampleGrid)   // rays from sample points in the target to the explosion
 * Exposure.FULL                    // ignore terrain entirely
 * }</pre>
 *
 * <p>Which blocks stop a ray is your {@link BlockView}; where the rays are aimed
 * is your {@link SampleGrid}. A version that changes either changes only those.
 */
@FunctionalInterface
public interface Exposure {

    /** @return the share of {@code target} the explosion at {@code origin} reaches, 0 to 1 */
    double of(Vec3 origin, Box target, BlockView blocks);

    /** Every target fully exposed. */
    Exposure FULL = (origin, target, blocks) -> 1d;

    /**
     * The share of sample points with a clear straight line to the explosion. Each
     * ray is cast from the point toward the explosion and is stopped by any block
     * shape it touches, including one it starts inside.
     */
    static Exposure sampled(SampleGrid grid) {
        Validate.notNull(grid, "grid");
        return (origin, target, blocks) -> {
            int[] counts = new int[2];
            grid.forEach(target, (x, y, z) -> {
                counts[0]++;
                if (Rays.clear(Vec3.of(x, y, z), origin, blocks)) {
                    counts[1]++;
                }
            });
            return counts[0] == 0 ? 0d : (double) counts[1] / counts[0];
        };
    }
}
