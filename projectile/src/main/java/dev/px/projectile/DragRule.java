package dev.px.projectile;

import dev.px.core.util.Validate;
import dev.px.core.world.CellTest;

/**
 * How much a projectile's velocity is kept each tick, where it is.
 *
 * <pre>{@code
 * DragRule.constant(0.99f)                                        // the same everywhere
 * DragRule.inside((x, y, z) -> Game.isWater(x, y, z), 0.6f, 0.99f)  // slower in water
 * (x, y, z) -> myOwnDrag(x, y, z)                                 // anything else
 * }</pre>
 *
 * <p>Asked once a tick with where the projectile was when the tick began. The
 * <a href="https://minecraft.wiki/w/Arrow#Movement">wiki</a> gives an arrow 0.99
 * in air and 0.6 in water, and says a
 * <a href="https://minecraft.wiki/w/Trident">trident</a> is not slowed down by
 * water; it does not say how the game decides a projectile is in water, so
 * {@link #inside} asks about the cell its position is in, and a different rule is
 * yours to write.
 */
@FunctionalInterface
public interface DragRule {

    /** @return the factor the velocity is multiplied by, for a projectile at this position */
    double at(double x, double y, double z);

    /** @return the same drag everywhere */
    static DragRule constant(double drag) {
        Validate.check(drag >= 0d, "drag can not be negative");
        return (x, y, z) -> drag;
    }

    /**
     * @param fluid  the cells that slow it differently, such as water
     * @param inside the drag while its position is in one of those cells
     * @param other  the drag anywhere else
     * @return a drag that depends on the cell the projectile's position is in
     */
    static DragRule inside(CellTest fluid, double inside, double other) {
        Validate.notNull(fluid, "fluid");
        Validate.check(inside >= 0d && other >= 0d, "drag can not be negative");
        return (x, y, z) -> fluid.test((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) ? inside : other;
    }
}
