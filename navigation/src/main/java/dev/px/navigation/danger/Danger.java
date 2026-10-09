package dev.px.navigation.danger;

import dev.px.core.movement.simulation.MotionState;

/**
 * What makes a route dangerous, for the local planner to weigh against time.
 *
 * <p>Asked once as each plan begins, for a {@link DangerField} covering the ticks
 * the plan could reach. {@link Hostiles} builds one from where the hostiles you
 * select are predicted to be; anything else &mdash; a region that will be
 * exploding, an area you would rather not cross &mdash; is a few lines against
 * this interface.
 *
 * <pre>{@code
 * Danger avoidSpawn = (start, ticks) -> (player, tick) -> player.intersects(spawn) ? 5d : 0d;
 * }</pre>
 */
@FunctionalInterface
public interface Danger {

    /** No danger anywhere: the planner weighs time alone. */
    Danger NONE = (start, ticks) -> DangerField.NONE;

    /**
     * @param start where the plan starts
     * @param ticks the furthest ahead the plan can look
     * @return the danger over those ticks; never null
     */
    DangerField at(MotionState start, int ticks);
}
