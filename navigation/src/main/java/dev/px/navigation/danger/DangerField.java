package dev.px.navigation.danger;

import dev.px.core.math.Box;

/**
 * How dangerous it is to be somewhere at some tick, frozen for one plan.
 *
 * <p>What a {@link Danger} hands the planner when a plan begins. The planner asks
 * it once for every tick of every move it tries, so it should be a lookup over
 * what was worked out up front, not a fresh prediction each time.
 */
@FunctionalInterface
public interface DangerField {

    /** Nothing is dangerous anywhere. */
    DangerField NONE = (player, tick) -> 0d;

    /**
     * @param player the player's hitbox where the move puts it
     * @param tick   ticks from now, 1 being the end of the first tick
     * @return the extra cost of being there then, in ticks: a cost of 10 makes a
     *         route through it as bad as one 10 ticks longer. Never negative
     */
    double cost(Box player, int tick);
}
