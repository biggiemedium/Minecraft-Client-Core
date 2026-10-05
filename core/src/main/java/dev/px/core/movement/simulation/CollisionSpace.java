package dev.px.core.movement.simulation;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.math.PhysicsProfile;

import java.util.Collections;
import java.util.List;

/**
 * What {@link Simulation} is allowed to ask about the world.
 *
 * <p>The same seam as {@link dev.px.core.util.spatial.PathSpace}, and for the
 * same reason: sweeping a hitbox against obstacles is arithmetic and belongs in
 * Core, while knowing that a block at these coordinates is a fence, a slab or
 * air belongs to the adapter. It is one method.
 *
 * <p>It returns boxes rather than a yes or no, which is the one place it has to
 * be richer than {@code PathSpace}. A grid search only needs to know whether a
 * cell is passable; a sweep needs to know <em>how far</em> the hitbox can travel
 * before it touches something, and that is a distance, not a flag.
 *
 * <pre>{@code
 * CollisionSpace world = region -> {
 *     List<Box> boxes = new ArrayList<>();
 *     for (int x = floor(region.getMinX()); x <= floor(region.getMaxX()); x++) {
 *         for (int y = floor(region.getMinY()); y <= floor(region.getMaxY()); y++) {
 *             for (int z = floor(region.getMinZ()); z <= floor(region.getMaxZ()); z++) {
 *                 IBlockState state = world.getBlockState(new BlockPos(x, y, z));
 *                 if (isSolid(state)) {
 *                     boxes.add(Box.block(x, y, z));       // or the real collision shape
 *                 }
 *             }
 *         }
 *     }
 *     return boxes;
 * };
 * }</pre>
 *
 * <p><b>Called once per simulated tick</b>, over the whole region the hitbox
 * could reach, so one call serves all three axis sweeps and the step-up retry.
 * That still means a twenty-tick prediction calls it twenty times, so keep it
 * cheap: a chunk-cache lookup is fine, anything that allocates heavily per block
 * becomes the prediction's running time.
 *
 * <p>Boxes may overlap, may extend outside the region, and may be returned in any
 * order. A block with a non-cube shape should return its real collision box,
 * because that is what the player will actually hit.
 */
@FunctionalInterface
public interface CollisionSpace {

    /**
     * @param region the volume the hitbox could touch this tick
     * @return every solid box intersecting it, or an empty list
     */
    List<Box> boxesIn(Box region);

    /**
     * @return how slippery the surface at {@code position} is, 1 being frictionless
     *
     * <p>Ice is 0.98, packed ice 0.98, slime 0.8, and everything else the default
     * below. Asked for the block the hitbox is standing on, and only while on the
     * ground.
     *
     * <p>Defaulted so the common case &mdash; a client that does not care about
     * ice &mdash; implements one method and gets ordinary ground everywhere.
     */
    default double slipperinessAt(Vec3 position) {
        return PhysicsProfile.vanilla().getDefaultSlipperiness();
    }

    /**
     * A world with nothing solid in it.
     *
     * <p>What {@link SimulationService} uses until a real one is installed, so a
     * simulation still runs and returns a ballistic trajectory rather than
     * throwing. Also the right space for a deliberate question: "where would this
     * go if nothing were in the way".
     */
    static CollisionSpace empty() {
        return region -> Collections.<Box>emptyList();
    }
}
