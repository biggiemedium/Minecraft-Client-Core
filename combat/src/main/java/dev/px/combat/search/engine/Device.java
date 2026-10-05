package dev.px.combat.search.engine;

import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;

/**
 * One kind of explosive, as {@link ExplosiveSearch} sees it: what it is and how
 * it hurts. {@link PlaceDevice} adds where one can be placed, and
 * {@link UseDevice} how to find and set off one already in the world.
 *
 * <p>Crystals and beds come with the library, in {@code CrystalSearch} and
 * {@code BedSearch}. Another explosive &mdash; a respawn anchor, or whatever a
 * version adds next &mdash; is a device of your own, and gets every threshold,
 * the branch and bound and the timing for nothing.
 *
 * @param <E> the game's type for what can be hurt
 */
public interface Device<E> {

    ExplosionModel<E> model();

    Explosive explosive();

    /** @return whether this explosive can go off here now; when not, nothing is searched. Always, unless overridden. */
    default boolean active() {
        return true;
    }
}
