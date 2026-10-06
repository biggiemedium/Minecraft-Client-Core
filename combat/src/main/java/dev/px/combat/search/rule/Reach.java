package dev.px.combat.search.rule;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;
import dev.px.core.world.Rays;

import java.util.function.DoubleSupplier;

/**
 * How far away you can place or break, from your eyes: a range, and a shorter
 * one for anything behind a wall.
 *
 * <pre>{@code
 * Reach place = Reach.of(placeRange::getDouble, placeWallRange::getDouble).measuredTo(ReachPoint.CENTRE);
 * }</pre>
 *
 * <p>Both are read on every search, so they follow your settings live. Something
 * further than the wall range counts only if a straight line from your eyes to
 * it is clear. The numbers are yours: how far a server lets you reach is a fact
 * about the server, not about this library.
 *
 * <p>Immutable.
 */
public final class Reach {

    private final DoubleSupplier range;
    private final DoubleSupplier wallRange;
    private final ReachPoint point;

    private Reach(DoubleSupplier range, DoubleSupplier wallRange, ReachPoint point) {
        this.range = range;
        this.wallRange = wallRange;
        this.point = point;
    }

    /** Measured to the {@link ReachPoint#CENTRE} unless {@link #measuredTo} says otherwise. */
    public static Reach of(DoubleSupplier range, DoubleSupplier wallRange) {
        return new Reach(Validate.notNull(range, "range"), Validate.notNull(wallRange, "wallRange"), ReachPoint.CENTRE);
    }

    public static Reach of(double range, double wallRange) {
        Validate.check(range >= 0d && wallRange >= 0d, "a reach must not be negative");
        return of(() -> range, () -> wallRange);
    }

    public Reach measuredTo(ReachPoint point) {
        return new Reach(range, wallRange, Validate.notNull(point, "point"));
    }

    /** @return the range now */
    public double range() {
        return Math.max(0d, range.getAsDouble());
    }

    /** @return the range behind a wall now, never more than {@link #range()} */
    public double wallRange() {
        return Math.min(range(), Math.max(0d, wallRange.getAsDouble()));
    }

    public ReachPoint getPoint() {
        return point;
    }

    /** @return how far {@code box} is from {@code eye}, measured to this reach's point */
    public double distance(Vec3 eye, Box box) {
        return point == ReachPoint.NEAREST ? box.distanceTo(eye) : box.getCenter().distanceTo(eye);
    }

    /**
     * @return whether {@code box} is in reach from {@code eye}: within range, and
     *         past the wall range only when the line to {@code sight} is clear
     */
    public boolean reaches(Vec3 eye, Box box, Vec3 sight, BlockView blocks) {
        double distance = distance(eye, box);
        return distance <= range() && (distance <= wallRange() || Rays.clear(eye, sight, blocks));
    }
}
