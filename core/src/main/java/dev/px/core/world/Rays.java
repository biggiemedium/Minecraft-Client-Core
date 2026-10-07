package dev.px.core.world;

import dev.px.core.math.Vec3;
import dev.px.core.util.spatial.VoxelRay;

/**
 * Whether a straight line gets through the blocks between two points, and if
 * not, where it first meets one.
 *
 * <p>Walks exactly the cells the line crosses, with Core's {@link VoxelRay}, and
 * tests the line against whatever {@link BlockView} says is in each one. Which
 * blocks stop it is entirely your game's answer; how a line crosses a grid is
 * geometry, and lives here.
 *
 * <pre>{@code
 * boolean seen = Rays.clear(eye, target, blocks);
 * RayHit hit = Rays.first(position, next, blocks);   // null when nothing is in the way
 * }</pre>
 *
 * <p>A cell's shape is tested only when the line crosses that cell, but then the
 * whole shape counts: a shape taller than its cell is met above it, by a line
 * that came through the cell, and not by one passing over the cell. That is how
 * the game's own raycast sees fences and walls, per the
 * <a href="https://minecraft.wiki/w/Projectile#Block_collisions">wiki</a>.
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

    /**
     * @return where the segment from {@code from} to {@code to} first meets a block
     *         shape, by the same rules as {@link #clear}; {@code null} when it
     *         meets none. The cells are tried in the order the line crosses them,
     *         and the first whose shape it meets is the hit &mdash; so a shape
     *         reaching out of its cell, tried first, can be met inside a block the
     *         line went through before it, as the game's own raycast meets it
     */
    public static RayHit first(Vec3 from, Vec3 to, BlockView blocks) {
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
            BlockShape shape = blocks.shapeAt(x, y, z);
            return shape.isEmpty() ? null : shape.hit(x, y, z, x1, y1, z1, x2, y2, z2);
        }
        RayHit[] hit = { null };
        VoxelRay.forEach(from, to.subtract(from), length, (cell, face, distance) -> {
            BlockShape shape = blocks.shapeAt(cell.getX(), cell.getY(), cell.getZ());
            if (!shape.isEmpty()) {
                hit[0] = shape.hit(cell.getX(), cell.getY(), cell.getZ(), x1, y1, z1, x2, y2, z2);
            }
            return hit[0] == null;
        });
        return hit[0];
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
