package dev.px.combat.anchor;

/**
 * Finds respawn anchors already in the world: your game's answer to "is an
 * anchor in this cell, and how many charges does it hold?".
 *
 * <pre>{@code
 * AnchorLookup anchors = (x, y, z) -> {
 *     BlockState state = mc.world.getBlockState(new BlockPos(x, y, z));
 *     return state.isOf(Blocks.RESPAWN_ANCHOR) ? state.get(RespawnAnchorBlock.CHARGES) : AnchorLookup.NONE;
 * };
 * }</pre>
 */
@FunctionalInterface
public interface AnchorLookup {

    /** What {@link #chargesAt} answers for a cell with no anchor in it. */
    int NONE = -1;

    /** @return the charges of the anchor in this cell, 0 for an empty one; {@link #NONE} when there is no anchor */
    int chargesAt(int x, int y, int z);
}
