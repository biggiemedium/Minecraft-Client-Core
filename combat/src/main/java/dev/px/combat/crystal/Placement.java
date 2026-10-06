package dev.px.combat.crystal;

import dev.px.core.math.Box;
import dev.px.core.util.Validate;
import dev.px.core.world.CellTest;
import dev.px.core.world.Obstructions;

/**
 * Whether a crystal can be placed on a block: your game's rule.
 *
 * <p>{@link #clearance} expresses the rule as the wiki describes it for current
 * Java &mdash; a base block, some clear blocks above it, and no entity in the
 * space above &mdash; with every part given by you, so a version that needs a
 * different number of clear blocks, or a taller entity check, is a different
 * number and nothing more:
 *
 * <pre>{@code
 * Placement.clearance(myBlocks::isCrystalBase, 2, myBlocks::isReplaceable, 2.0);   // two clear blocks
 * Placement.clearance(myBlocks::isCrystalBase, 1, myBlocks::isReplaceable, 2.0);   // one, if your version needs one
 * }</pre>
 *
 * <p>Check your version's rule rather than trusting folklore: the number of clear
 * blocks is one of the things players disagree about.
 */
@FunctionalInterface
public interface Placement {

    /**
     * @param x the base block a crystal would sit on
     * @return whether a crystal can be placed there now
     */
    boolean canPlace(int x, int y, int z, Obstructions entities);

    /**
     * A crystal goes on a base block when the blocks above it are clear and no
     * entity is in the space above it.
     *
     * @param base which blocks a crystal can sit on
     * @param clearBlocks how many blocks directly above the base must pass {@code clear}
     * @param clear whether a block counts as clear: air, or replaceable, as your game says
     * @param entityHeight how tall the space above the base is that must hold no entity;
     *        it is the base block's footprint, starting at its top face
     */
    static Placement clearance(CellTest base, int clearBlocks, CellTest clear, double entityHeight) {
        Validate.notNull(base, "base");
        Validate.notNull(clear, "clear");
        Validate.check(clearBlocks >= 0, "clearBlocks must not be negative");
        Validate.check(entityHeight >= 0d, "entityHeight must not be negative");
        return (x, y, z, entities) -> {
            if (!base.test(x, y, z)) {
                return false;
            }
            for (int above = 1; above <= clearBlocks; above++) {
                if (!clear.test(x, y + above, z)) {
                    return false;
                }
            }
            return entityHeight <= 0d || !entities.any(Box.of(x, y + 1, z, x + 1, y + 1 + entityHeight, z + 1));
        };
    }
}
