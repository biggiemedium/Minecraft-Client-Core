package dev.px.core.navigation;

import dev.px.core.movement.simulation.MotionState;

/**
 * Something that knows the way: a pathfinder of any kind, behind one interface.
 *
 * <p>Two kinds, and {@link #drives()} says which:
 *
 * <ul>
 *   <li><b>Plans only.</b> {@link #plan} returns a {@link Route} and does nothing
 *       else; whoever asked follows it through Core's controls. The navigation
 *       module's local planner is one, and so is any pathfinder that hands back a
 *       list of blocks.
 *   <li><b>Drives.</b> It moves the player itself, as Baritone does:
 *       {@link #follow} is called every tick and reports {@link Progress}, and
 *       {@link #cancel} stops it. Its adapter routes the rotations and keys it
 *       sets through Core's claims as far as its own API allows.
 * </ul>
 *
 * <pre>{@code
 * // a provider that only plans
 * PathProvider grid = (goal, from) -> toRoute(goal, myAStar.find(from.getPosition(), goal));
 *
 * // one that drives: yours, wrapping a pathfinder that moves the player itself
 * public final class BaritoneProvider implements PathProvider {
 *     public boolean drives() { return true; }
 *     public Progress follow(Goal goal) { ... }
 *     public void cancel() { ... }
 * }
 * }</pre>
 *
 * <p>The contract lives in Core so anything can ask to go somewhere without
 * depending on the navigation module, and a pathfinder that depends on the game
 * &mdash; Baritone does &mdash; is wrapped in the client, never in the library.
 *
 * <p>Called on the game thread.
 */
@FunctionalInterface
public interface PathProvider {

    /**
     * Plans a way from {@code from} to {@code goal}, for a provider that only plans.
     *
     * @return a route, which may stop short of the goal (see
     *         {@link Route#isComplete()}); or null when there is no way at all, or
     *         this provider drives instead
     */
    Route plan(Goal goal, MotionState from);

    /** @return whether this provider moves the player itself; false for one that only plans */
    default boolean drives() {
        return false;
    }

    /**
     * Moves towards {@code goal}, for a provider that drives. Called every tick
     * for as long as the goal is wanted; a different goal than last time replaces it.
     *
     * @return how it is going
     */
    default Progress follow(Goal goal) {
        return Progress.failed(getClass().getSimpleName() + " only plans; it cannot drive");
    }

    /** Stops whatever {@link #follow} started. Called when the goal is no longer wanted. */
    default void cancel() {
    }
}
