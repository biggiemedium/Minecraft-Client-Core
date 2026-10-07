package dev.px.projectile.flight;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.List;

/**
 * Where a projectile goes: its position after every tick, its velocity, and what
 * stopped it.
 *
 * <pre>{@code
 * Trajectory path = flight.launch(pearl.launch(you, rotation));
 * for (int i = 1; i < path.getPoints().size(); i++) {
 *     drawLine(path.at(i - 1), path.at(i));              // your renderer, your colours
 * }
 * drawBox(path.landingSpread());                        // where its randomness could land it instead
 * }</pre>
 *
 * <p>Point 0 is where it starts; point {@code i} is where it is after tick
 * {@code i}. The last point is where it ended: on what it hit, or wherever it
 * was when the flight ran out of ticks.
 *
 * <p>Immutable.
 */
public final class Trajectory {

    private final List<Vec3> points;
    private final List<Vec3> velocities;
    private final double[] reach;
    private final double spread;
    private final Hit hit;

    Trajectory(List<Vec3> points, List<Vec3> velocities, double[] reach, double spread, Hit hit) {
        this.points = Collections.unmodifiableList(points);
        this.velocities = Collections.unmodifiableList(velocities);
        this.reach = reach;
        this.spread = spread;
        this.hit = hit;
    }

    /** @return its position after each tick, from where it started to where it ended */
    public List<Vec3> getPoints() {
        return points;
    }

    /** @return its position after {@code tick} ticks; 0 is where it started */
    public Vec3 at(int tick) {
        Validate.check(tick >= 0 && tick < points.size(), "no tick " + tick + " in a flight of " + getTicks());
        return points.get(tick);
    }

    /** @return its velocity after {@code tick} ticks, in blocks per tick; at the moment it hit, for the last */
    public Vec3 velocityAt(int tick) {
        Validate.check(tick >= 0 && tick < velocities.size(), "no tick " + tick + " in a flight of " + getTicks());
        return velocities.get(tick);
    }

    /** @return how many ticks it flew, counting the one it hit in */
    public int getTicks() {
        return points.size() - 1;
    }

    /** @return where it started */
    public Vec3 getStart() {
        return points.get(0);
    }

    /** @return where it ended */
    public Vec3 getEnd() {
        return points.get(points.size() - 1);
    }

    /** @return what ended it; never null, {@link Hit.Type#NONE} if nothing did */
    public Hit getHit() {
        return hit;
    }

    /**
     * @return how far from {@link #at(int)} its launch's randomness could have
     *         moved it by then, on each axis; 0 for a flight not made from a
     *         launch, or one with no spread
     *
     * <p>A bound, not a guess: the randomness pushes each axis of its starting
     * velocity by at most the launch's spread, and that push carries through
     * every tick the way the starting velocity does. It does not know the other
     * paths would hit something else.
     */
    public double spreadAt(int tick) {
        Validate.check(tick >= 0 && tick < points.size(), "no tick " + tick + " in a flight of " + getTicks());
        return spread * reach[tick];
    }

    /** @return the box around where it ended that its randomness could have landed it in instead */
    public Box landingSpread() {
        double half = spreadAt(getTicks());
        Vec3 end = getEnd();
        return Box.of(end.getX() - half, end.getY() - half, end.getZ() - half,
                end.getX() + half, end.getY() + half, end.getZ() + half);
    }

    @Override
    public String toString() {
        return "Trajectory(" + getTicks() + " ticks, " + hit + ")";
    }
}
