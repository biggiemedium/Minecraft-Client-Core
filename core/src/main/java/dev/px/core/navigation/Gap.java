package dev.px.core.navigation;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * How far a position is from a {@link Goal}, at least: across, and up or down.
 *
 * <p>Split by direction because each is covered at a different speed &mdash;
 * walking across, jumping up, falling down &mdash; so a planner turns a gap into
 * the fewest ticks it could take only by knowing which is which. A single
 * distance would have to be divided by the fastest of the three, falling, and
 * would say almost nothing about a walk.
 *
 * <p>A lower bound, never an estimate that could be too high: the position is at
 * least this far across and at least this far up or down from anywhere the goal
 * is met. At most one of {@link #getRise()} and {@link #getDrop()} is above zero,
 * except in the {@link #max} of a goal needing both, which can never be met.
 *
 * <p>Immutable.
 */
@Getter
@EqualsAndHashCode
public final class Gap {

    /** Already there, or nothing known: no distance at all. */
    public static final Gap NONE = new Gap(0d, 0d, 0d);

    /** Horizontal blocks still to cover. */
    private final double across;

    /** Blocks to climb. */
    private final double rise;

    /** Blocks to drop. */
    private final double drop;

    private Gap(double across, double rise, double drop) {
        this.across = across;
        this.rise = rise;
        this.drop = drop;
    }

    /**
     * @param across horizontal blocks, never negative
     * @param up     blocks to climb, or negative for blocks to drop
     */
    public static Gap of(double across, double up) {
        double a = Math.max(0d, across);
        if (a == 0d && up == 0d) {
            return NONE;
        }
        return new Gap(a, Math.max(0d, up), Math.max(0d, -up));
    }

    /** @return the straight-line distance this gap is at least, for "how far is left" */
    public double distance() {
        double vertical = Math.max(rise, drop);
        return Math.sqrt(across * across + vertical * vertical);
    }

    /** @return whether there is no distance left at all */
    public boolean isNone() {
        return across == 0d && rise == 0d && drop == 0d;
    }

    /**
     * @return a gap no larger than either, each part the smaller: a lower bound
     *         for reaching whichever of two goals is nearer
     */
    public Gap min(Gap other) {
        double up = Math.min(rise, other.rise);
        double down = Math.min(drop, other.drop);
        return new Gap(Math.min(across, other.across), up, down);
    }

    /**
     * @return a gap each part the larger: a lower bound for reaching somewhere
     *         both goals are met
     */
    public Gap max(Gap other) {
        return new Gap(Math.max(across, other.across), Math.max(rise, other.rise), Math.max(drop, other.drop));
    }

    @Override
    public String toString() {
        return String.format("Gap(across=%.3f, %s)", across,
                rise > 0d ? String.format("up %.3f", rise) : drop > 0d ? String.format("down %.3f", drop) : "level");
    }
}
