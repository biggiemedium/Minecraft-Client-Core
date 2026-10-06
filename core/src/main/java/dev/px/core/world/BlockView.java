package dev.px.core.world;

import dev.px.core.math.Vec3i;

/**
 * Your game's blocks, as lines through the world see them: what is in each
 * cell. One small class per version.
 *
 * <pre>{@code
 * public final class LegacyBlocks implements BlockView {
 *     public BlockShape shapeAt(int x, int y, int z) {
 *         IBlockState state = mc.theWorld.getBlockState(new BlockPos(x, y, z));
 *         return shapes.computeIfAbsent(state, this::shapeOf);   // build once per state, reuse
 *     }
 * }
 * }</pre>
 *
 * <p>Return the shape that stops a line through the cell in your game: an
 * explosion's rays, a line of sight, a reach check. Core decides nothing about
 * which blocks those are; a version that changes it changes only this method.
 * {@link Rays} walks a line through it.
 *
 * <p>The same seam as {@link dev.px.core.movement.simulation.CollisionSpace} and
 * {@link dev.px.core.util.spatial.PathSpace}, each asking the one question its
 * algorithm needs: boxes to sweep a hitbox against, passable cells to search,
 * and here, shapes for a line to cross.
 *
 * <p>Called often &mdash; dozens of times per ray &mdash; on the game thread.
 * Return shared {@link BlockShape} instances rather than building new ones.
 */
@FunctionalInterface
public interface BlockView {

    /** @return what stops a line through this cell; {@link BlockShape#EMPTY} for nothing */
    BlockShape shapeAt(int x, int y, int z);

    /** A world with nothing in it, for tests and for ignoring terrain. */
    BlockView EMPTY = (x, y, z) -> BlockShape.EMPTY;

    /**
     * These blocks with {@code cells} empty: the world as something that is
     * itself a block sees it, once it is gone. A block that explodes is removed
     * first, so its own cells must not stop its rays.
     *
     * @param cells a few cells; each lookup checks every one
     */
    default BlockView without(Vec3i... cells) {
        Vec3i[] gone = cells.clone();
        BlockView blocks = this;
        return (x, y, z) -> {
            for (Vec3i cell : gone) {
                if (cell.getX() == x && cell.getY() == y && cell.getZ() == z) {
                    return BlockShape.EMPTY;
                }
            }
            return blocks.shapeAt(x, y, z);
        };
    }
}
