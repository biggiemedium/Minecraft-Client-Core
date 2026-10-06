package dev.px.combat.search.engine;

import dev.px.combat.search.rule.Reach;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.world.BlockView;

import java.util.function.Consumer;

/**
 * Explosives already in the world, and setting one off: breaking a crystal,
 * using a bed.
 *
 * @param <E> the game's type for what can be hurt
 * @param <X> one explosive: a crystal's {@code Tracked}, a {@code Bed}
 */
public interface UseDevice<E, X> extends Device<E> {

    /** Offers every one that might be within {@code range} of {@code eye}; {@link #inReach} decides. */
    void forEachWithin(Vec3 eye, double range, Consumer<? super X> sink);

    /** @return whether it can be set off from {@code eye}: in range, and seen past the wall range */
    boolean inReach(X explosive, Vec3 eye, Reach reach);

    /** @return where it explodes from */
    Vec3 origin(X explosive);

    /** @return the blocks as its explosion would find them */
    BlockView blocksWhenFired(X explosive);

    /**
     * @return where you would look to set it off: what your {@code AimCost} is
     *         asked about. Asked only once {@link #inReach} said yes. Where it
     *         explodes from, unless overridden.
     */
    default Vec3 aim(X explosive) {
        return origin(explosive);
    }

    /**
     * @return whether your server would accept setting it off from {@code eye}:
     *         for one you click, a click your rules allow exists. Always, unless
     *         overridden. Asked before {@link #aim(Vec3, Object)}
     */
    default boolean clickable(Vec3 eye, X explosive) {
        return true;
    }

    /** @return where you would look from {@code eye} to set it off: {@link #aim(Object)} unless overridden */
    default Vec3 aim(Vec3 eye, X explosive) {
        return aim(explosive);
    }

    /**
     * @return the room it takes up: matched against what you placed, to know it
     *         as yours. Null unless overridden, which never matches
     */
    default Box occupies(X explosive) {
        return null;
    }

    /** @return what it is remembered by between ticks, for inhibit: equal keys are the same explosive */
    Object key(X explosive);

    /** @return how many ticks it has existed; {@code Integer.MAX_VALUE} when unknown, which no minimum age stops */
    int age(X explosive);
}
