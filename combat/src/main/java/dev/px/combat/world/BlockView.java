package dev.px.combat.world;

import dev.px.core.math.Vec3i;

/**
 * Your game's blocks, as far as combat needs them. One small class per version.
 *
 * <pre>{@code
 * public final class LegacyBlocks implements BlockView, CellTest {
 *     public BlockShape shapeAt(int x, int y, int z) {
 *         IBlockState state = mc.theWorld.getBlockState(new BlockPos(x, y, z));
 *         return shapes.computeIfAbsent(state, this::shapeOf);   // build once per state, reuse
 *     }
 * }
 * }</pre>
 *
 * <p>Return the shape your game's explosion rays are stopped by. Core decides
 * nothing about which blocks those are: a version that changes it changes only
 * this method.
 *
 * <p>Called often &mdash; dozens of times per ray &mdash; on the game thread.
 * Return shared {@link BlockShape} instances rather than building new ones.
 */
@FunctionalInterface
public interface BlockView {

    /** @return what stops an explosion's rays in this cell; {@link BlockShape#EMPTY} for nothing */
    BlockShape shapeAt(int x, int y, int z);

    /** A world with nothing in it, for tests and for ignoring terrain. */
    BlockView EMPTY = (x, y, z) -> BlockShape.EMPTY;

    /**
     * These blocks with {@code cells} empty: the world as an explosive that is
     * itself a block sees it, once it is gone. A bed is removed before it
     * explodes, so its own cells must not stop its rays.
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
