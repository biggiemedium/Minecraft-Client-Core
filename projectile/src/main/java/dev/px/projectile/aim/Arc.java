package dev.px.projectile.aim;

/**
 * Which of the two ways to reach a point an aim takes.
 *
 * <pre>{@code
 * AimSolver.builder(flight, bow).arcs(Arc.LOW, Arc.HIGH)   // low, or over the wall if low is blocked
 * }</pre>
 *
 * <p>Anything a projectile can reach and not at the edge of its range, it can
 * reach two ways: flat and fast, or lobbed high and slow.
 */
public enum Arc {

    /** The flatter path: arrives sooner, and gives a moving target less time. */
    LOW,
    /** The higher path: over what is in the way, but slower. */
    HIGH
}
