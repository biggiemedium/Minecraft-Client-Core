package dev.px.combat.anchor;

import dev.px.core.math.Box;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;
import dev.px.core.world.CellTest;
import dev.px.core.world.Obstructions;

/**
 * Whether a respawn anchor can be placed in a cell: your game's rule.
 *
 * <p>An anchor is a whole block, placed like any other: into a cell that can
 * take it, with nobody standing in it, against something to click.
 * {@link #clearance} is the room, and {@link #nextTo} the something to click,
 * for when the search is not given click rules that say so themselves:
 *
 * <pre>{@code
 * AnchorPlacement.clearance(myBlocks::isReplaceable);                              // with click rules
 * AnchorPlacement.clearance(myBlocks::isReplaceable).nextTo(myBlocks::isSolid);    // without
 * }</pre>
 */
@FunctionalInterface
public interface AnchorPlacement {

    /** @return whether an anchor can be placed into {@code cell} now */
    boolean canPlace(Vec3i cell, Obstructions entities);

    /**
     * An anchor goes where the cell is clear and no entity is anywhere in it.
     *
     * @param clear whether a cell can take a block: air, or replaceable, as your game says
     */
    static AnchorPlacement clearance(CellTest clear) {
        Validate.notNull(clear, "clear");
        return (cell, entities) -> clear.test(cell.getX(), cell.getY(), cell.getZ())
                && !entities.any(Box.block(cell.getX(), cell.getY(), cell.getZ()));
    }

    /** This rule, and also a block beside the cell, on any side, must pass {@code support}. */
    default AnchorPlacement nextTo(CellTest support) {
        Validate.notNull(support, "support");
        AnchorPlacement room = this;
        return (cell, entities) -> {
            if (!room.canPlace(cell, entities)) {
                return false;
            }
            for (Direction side : Direction.values()) {
                Vec3i beside = cell.offset(side);
                if (support.test(beside.getX(), beside.getY(), beside.getZ())) {
                    return true;
                }
            }
            return false;
        };
    }
}
