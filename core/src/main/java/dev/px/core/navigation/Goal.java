package dev.px.core.navigation;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.function.Supplier;

/**
 * Somewhere to get to, as plain geometry.
 *
 * <p>A goal answers two questions about the player's feet: is this there yet
 * ({@link #isMet}), and how far is it at least ({@link #gap}). Any
 * {@link PathProvider} can be asked to reach one, which is what lets a flow step,
 * a combat feature or a module ask to go somewhere without knowing whether
 * Baritone or the navigation module's own planner takes it there.
 *
 * <pre>{@code
 * Goal.block(x, y, z)                    // the feet in that block
 * Goal.near(point, 2)                    // within two blocks of a point
 * Goal.near(target, 4)                   // within four blocks of someone, wherever they go
 * Goal.column(x, z)                      // anywhere in that column
 * Goal.level(y)                          // any block at that height
 * Goal.avoid(region)                     // anywhere out of a region
 * Goal.anyOf(home, Goal.near(bed, 1))    // whichever is nearer
 * Goal.allOf(Goal.column(x, z), Goal.avoid(lava))
 * }</pre>
 *
 * <p>Positions are the feet, as everywhere in Core: the bottom centre of the
 * hitbox. Nothing here knows what a block is; a goal that depends on the world
 * ("next to a chest") is yours, built from these or written against this
 * interface.
 *
 * <p>Goals that follow something read it live, every time they are asked, and
 * report where it is through {@link #anchor()} so a navigator knows to replan
 * once it has moved.
 */
public interface Goal {

    /** @return whether {@code feet} is somewhere this goal is met */
    boolean isMet(Vec3 feet);

    /**
     * @return how far {@code feet} is from anywhere this goal is met, at least:
     *         never more than the truth, or a planner using it may miss the best
     *         route. {@link Gap#NONE} when it is met, or when nothing better is known
     */
    Gap gap(Vec3 feet);

    /**
     * @return the point this goal is built around, read now: for a goal that
     *         follows something, where that is. Null for goals with no single
     *         point, such as a level or a region to avoid
     */
    default Vec3 anchor() {
        return null;
    }

    // ------------------------------------------------------------ shapes

    /** @return met when the feet are in this block: at or above its floor and below its top */
    static Goal block(Vec3i cell) {
        Validate.notNull(cell, "cell");
        return new Goals.InBlock(cell);
    }

    static Goal block(int x, int y, int z) {
        return block(Vec3i.of(x, y, z));
    }

    /** @return met within {@code radius} blocks of {@code point}, measured from the feet */
    static Goal near(Vec3 point, double radius) {
        Validate.notNull(point, "point");
        return near(() -> point, radius);
    }

    /**
     * @return met within {@code radius} blocks of wherever {@code point} says,
     *         read each time the goal is asked: a goal that moves
     */
    static Goal near(Supplier<Vec3> point, double radius) {
        Validate.notNull(point, "point");
        Validate.check(radius >= 0d, "radius must not be negative, got " + radius);
        return new Goals.Near(point, radius);
    }

    /**
     * @return met within {@code radius} blocks of an entity's feet, wherever it
     *         goes. {@link Tracked} is updated in place, so this follows it for as
     *         long as its tracker keeps it
     */
    static Goal near(Tracked<?> entity, double radius) {
        Validate.notNull(entity, "entity");
        return near(entity::getPosition, radius);
    }

    /** @return met anywhere in this column, at any height */
    static Goal column(int x, int z) {
        return new Goals.Column(x, z);
    }

    /** @return met with the feet in any block at height {@code y} */
    static Goal level(int y) {
        return new Goals.Level(y);
    }

    /**
     * @return met with the feet anywhere outside {@code region}
     *
     * <p>Its gap is always {@link Gap#NONE}: the way out could be up, down or
     * sideways, so nothing more can be promised. Combine it with a goal that has a
     * gap to give a planner direction.
     */
    static Goal avoid(Box region) {
        Validate.notNull(region, "region");
        return new Goals.Avoid(region);
    }

    /** @return met when any of {@code goals} is met */
    static Goal anyOf(Goal... goals) {
        return new Goals.AnyOf(Goals.copy(goals));
    }

    /** @return met only where every one of {@code goals} is met */
    static Goal allOf(Goal... goals) {
        return new Goals.AllOf(Goals.copy(goals));
    }
}
