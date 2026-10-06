package dev.px.combat.bed;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;
import dev.px.core.world.CellTest;
import dev.px.core.world.Obstructions;

/**
 * Whether a bed can be placed: your game's rule.
 *
 * <p>The wiki gives current Java as: a bed needs two blocks of room, the foot on
 * the block you select and the head one further on in the direction you face,
 * and no blocks beneath it. Bedrock, and Java between 17w47a and 18w22a, wanted
 * solid blocks underneath. {@link #clearance} is the room, and
 * {@link #onlyAbove} adds the support for a version that wants it:
 *
 * <pre>{@code
 * BedPlacement.clearance(myBlocks::isReplaceable, 0.5625, 0.5625);                        // anywhere with room
 * BedPlacement.clearance(myBlocks::isReplaceable, 0.5625, 0.5625).onlyAbove(myBlocks::isSolid);   // with support
 * }</pre>
 *
 * <p>How tall a space must be free of entities, and for which half, are your
 * numbers: the wiki says only that a bed is "half a block tall".
 */
@FunctionalInterface
public interface BedPlacement {

    /** @return whether {@code bed} can be placed now */
    boolean canPlace(Bed bed, Obstructions entities);

    /**
     * A bed goes where both its cells are clear and no entity is in the way.
     *
     * @param clear whether a cell can take half a bed: air, or replaceable, as your game says
     * @param footEntityHeight how tall a space at the bottom of the foot's cell must hold
     *        no entity; 0 when entities never stop it
     * @param headEntityHeight the same, for the head's cell
     */
    static BedPlacement clearance(CellTest clear, double footEntityHeight, double headEntityHeight) {
        Validate.notNull(clear, "clear");
        Validate.check(footEntityHeight >= 0d && headEntityHeight >= 0d, "an entity height must not be negative");
        return (bed, entities) -> {
            Vec3i foot = bed.getFoot();
            Vec3i head = bed.getHead();
            return clear.test(foot.getX(), foot.getY(), foot.getZ())
                    && clear.test(head.getX(), head.getY(), head.getZ())
                    && (footEntityHeight <= 0d || !entities.any(Box.of(foot.getX(), foot.getY(), foot.getZ(),
                            foot.getX() + 1, foot.getY() + footEntityHeight, foot.getZ() + 1)))
                    && (headEntityHeight <= 0d || !entities.any(Box.of(head.getX(), head.getY(), head.getZ(),
                            head.getX() + 1, head.getY() + headEntityHeight, head.getZ() + 1)));
        };
    }

    /** This rule, and also both cells beneath the bed must pass {@code support}. */
    default BedPlacement onlyAbove(CellTest support) {
        Validate.notNull(support, "support");
        BedPlacement room = this;
        return (bed, entities) -> {
            Vec3i foot = bed.getFoot();
            Vec3i head = bed.getHead();
            return support.test(foot.getX(), foot.getY() - 1, foot.getZ())
                    && support.test(head.getX(), head.getY() - 1, head.getZ())
                    && room.canPlace(bed, entities);
        };
    }
}
