package dev.px.combat.world;

import dev.px.core.math.Vec3;
import dev.px.core.util.spatial.VoxelRay;

/**
 * Whether a straight line gets through the blocks between two points.
 *
 * <p>Walks exactly the cells the line crosses, with Core's {@link VoxelRay}, and
 * tests the line against whatever {@link BlockView} says is in each one. Which
 * blocks stop it is entirely your game's answer; how a line crosses a grid is
 * geometry, and lives here.
 */
public final class Rays {

    private Rays() {
    }

    /**
     * @return whether the segment from {@code from} to {@code to} passes through
     *         no block shape. A segment that starts inside a shape is blocked; one
     *         running along the top of a block is not, and one along the seam
     *         between two stacked blocks is
     */
    public static boolean clear(Vec3 from, Vec3 to, BlockView blocks) {
        double x1 = from.getX();
        double y1 = from.getY();
        double z1 = from.getZ();
        double x2 = to.getX();
        double y2 = to.getY();
        double z2 = to.getZ();
        double length = from.distanceTo(to);
        if (length < 1e-9) {
            int x = floor(x1);
            int y = floor(y1);
            int z = floor(z1);
            return !blocks.shapeAt(x, y, z).intersects(x, y, z, x1, y1, z1, x2, y2, z2);
        }
        boolean[] blocked = { false };
        VoxelRay.forEach(from, to.subtract(from), length, (cell, face, distance) -> {
            BlockShape shape = blocks.shapeAt(cell.getX(), cell.getY(), cell.getZ());
            if (!shape.isEmpty() && shape.intersects(cell.getX(), cell.getY(), cell.getZ(), x1, y1, z1, x2, y2, z2)) {
                blocked[0] = true;
                return false;
            }
            return true;
        });
        return !blocked[0];
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
