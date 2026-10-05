package dev.px.combat.search.engine;

import dev.px.combat.search.rule.Reach;
import dev.px.combat.world.BlockView;
import dev.px.core.math.Vec3;

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

    /** @return what it is remembered by between ticks, for inhibit: equal keys are the same explosive */
    Object key(X explosive);

    /** @return how many ticks it has existed; {@code Integer.MAX_VALUE} when unknown, which no minimum age stops */
    int age(X explosive);
}
