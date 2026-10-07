package dev.px.projectile.aim;

import lombok.Getter;

/**
 * What the last aim found and how hard it worked: an aim, or why there was none.
 *
 * <pre>{@code
 * Aim shot = solver.at(you, target);
 * if (shot == null && solver.getLastStats().getOutcome() == AimStats.Outcome.BLOCKED) {
 *     // something is in the way: wait, or move
 * }
 * }</pre>
 *
 * <p>Immutable.
 */
@Getter
public final class AimStats {

    /** How an aim ended. */
    public enum Outcome {
        /** An aim was found. */
        AIMED,
        /** No angle reaches the point with this power, or it is straight above or below. */
        OUT_OF_RANGE,
        /** Every arc that reaches it hits something else first. */
        BLOCKED,
        /**
         * The target's expected position kept changing the flight time and the
         * flight time kept changing the position: it moves too erratically, or
         * too fast, for an aim to settle.
         */
        NO_CONVERGENCE
    }

    private final Outcome outcome;
    /** Paths flown through nothing while searching for an angle. */
    private final int motions;
    /** Paths flown through the world to check an angle: one per arc tried. */
    private final int flights;
    /** Times the target's expected position was looked up and the aim redone. */
    private final int rounds;
    /** Arcs that reached the point but hit something on the way. */
    private final int blockedArcs;

    AimStats(Outcome outcome, int motions, int flights, int rounds, int blockedArcs) {
        this.outcome = outcome;
        this.motions = motions;
        this.flights = flights;
        this.rounds = rounds;
        this.blockedArcs = blockedArcs;
    }

    @Override
    public String toString() {
        return String.format("AimStats(%s, %d motions, %d flights, %d rounds, %d blocked)",
                outcome, motions, flights, rounds, blockedArcs);
    }
}
