package dev.px.navigation.danger;

import dev.px.core.entity.Tracked;

/**
 * How dangerous one hostile is, at some distance: your rule.
 *
 * <p>A mob's reach, how hard it hits and whether it explodes are facts about the
 * game, so they are yours to write here, not the library's.
 *
 * <pre>{@code
 * // three blocks covers a zombie's reach; past that, keep a little distance anyway
 * DangerRule<Mob> rule = (mob, distance, tick) -> distance < 3 ? 20 : distance < 6 ? 2 : 0;
 * }</pre>
 *
 * @param <E> the game's type for the hostiles
 */
@FunctionalInterface
public interface DangerRule<E> {

    /**
     * @param hostile  the hostile as it is now; read its type, its health, whatever matters
     * @param distance blocks between the player's hitbox and the hostile's at
     *                 {@code tick}, where each is expected to be; 0 when they touch
     * @param tick     ticks from now
     * @return the extra cost, in ticks, of the player being there then; 0 for none
     */
    double cost(Tracked<E> hostile, double distance, int tick);
}
