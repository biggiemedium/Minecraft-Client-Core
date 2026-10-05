package dev.px.combat.bed;

import dev.px.core.math.Direction;

/**
 * Finds beds already in the world: your game's answer to "is a bed's head in
 * this cell, and which way does it face?".
 *
 * <pre>{@code
 * BedLookup beds = (x, y, z) -> {
 *     IBlockState state = mc.theWorld.getBlockState(new BlockPos(x, y, z));
 *     if (!(state.getBlock() instanceof BlockBed) || state.getValue(BlockBed.PART) != EnumPartType.HEAD) {
 *         return null;
 *     }
 *     return Directions.of(state.getValue(BlockBed.FACING));        // your facing type onto Core's
 * };
 * }</pre>
 *
 * <p>Answer for the head only, so each bed is found once.
 */
@FunctionalInterface
public interface BedLookup {

    /** @return the way the bed whose head is in this cell faces, foot to head; null if no bed's head is here */
    Direction headAt(int x, int y, int z);
}
