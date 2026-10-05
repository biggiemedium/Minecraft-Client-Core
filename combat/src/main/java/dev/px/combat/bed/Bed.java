package dev.px.combat.bed;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

/**
 * Where a bed is, or would be: its foot, and the way it faces. The head is one
 * block further on, in that direction.
 *
 * <pre>{@code
 * Bed bed = Bed.of(Vec3i.of(10, 64, 3), Direction.NORTH);   // foot at 10, 64, 3; head at 10, 64, 2
 * }</pre>
 *
 * <p>Two beds are equal when they cover the same cells the same way round, so a
 * bed can be remembered across ticks by value: the search's inhibit does.
 *
 * <p>Immutable.
 */
public final class Bed {

    private final Vec3i foot;
    private final Direction facing;
    private final Vec3i head;

    private Bed(Vec3i foot, Direction facing) {
        this.foot = foot;
        this.facing = facing;
        this.head = foot.offset(facing);
    }

    /**
     * @param foot the cell the foot is in: the block you select when placing
     * @param facing the way you face when placing; the head goes this way
     */
    public static Bed of(Vec3i foot, Direction facing) {
        Validate.notNull(foot, "foot");
        Validate.notNull(facing, "facing");
        Validate.check(facing.isHorizontal(), "a bed faces one of the four compass directions");
        return new Bed(foot, facing);
    }

    /** @return the bed whose head is at {@code head}, facing {@code facing} */
    public static Bed ofHead(Vec3i head, Direction facing) {
        Validate.notNull(head, "head");
        Validate.notNull(facing, "facing");
        return of(head.offset(facing.opposite()), facing);
    }

    public Vec3i getFoot() {
        return foot;
    }

    public Vec3i getHead() {
        return head;
    }

    /** @return the way it faces, foot to head: the way you face to place it */
    public Direction getFacing() {
        return facing;
    }

    /** @return the cell one half is in */
    public Vec3i get(BedPart part) {
        return part == BedPart.HEAD ? head : foot;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Bed)) {
            return false;
        }
        Bed bed = (Bed) other;
        return foot.equals(bed.foot) && facing == bed.facing;
    }

    @Override
    public int hashCode() {
        return 31 * foot.hashCode() + facing.hashCode();
    }

    @Override
    public String toString() {
        return "Bed(foot " + foot + ", facing " + facing + ")";
    }
}
