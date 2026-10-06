package dev.px.combat.search.engine;

import dev.px.combat.world.BlockView;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;

import java.util.function.Consumer;

/**
 * Where an explosive can be placed: the spots in one block cell, and how one
 * placed there would explode.
 *
 * <p>The search scans every cell in reach and asks for the spots in each. A
 * crystal has at most one per cell, its base; a bed has up to four, one per way
 * it can face.
 *
 * @param <E> the game's type for what can be hurt
 * @param <S> a spot: whatever says where and how to place
 */
public interface PlaceDevice<E, S> extends Device<E> {

    /** Offers every spot that can be placed now whose reach is measured to this cell. Called once per cell in reach. */
    void spotsAt(int x, int y, int z, Consumer<? super S> sink);

    /** @return whether this cell can be seen from {@code eye}: asked only past the wall range, once per cell with spots */
    boolean visible(Vec3 eye, int x, int y, int z);

    /** @return where an explosive placed at {@code spot} would explode from */
    Vec3 origin(S spot);

    /** @return the blocks as that explosion would find them */
    BlockView blocksWhenFired(S spot);

    /**
     * @return the room an explosive placed at {@code spot} takes up until it goes
     *         off: what a pending placement keeps others out of. Null, nothing
     *         kept out, unless overridden
     */
    default Box occupies(S spot) {
        return null;
    }

    /**
     * @return the room that must be free of anything placed and not yet shown to
     *         place at {@code spot}. What it would occupy, unless overridden
     */
    default Box needs(S spot) {
        return occupies(spot);
    }

    /**
     * @return where you would look to place at {@code spot}: what your
     *         {@code AimCost} is asked about. Where it explodes from, unless overridden.
     */
    default Vec3 aim(S spot) {
        return origin(spot);
    }

    /**
     * @return whether your server would accept placing at {@code spot} from
     *         {@code eye}: a click your rules allow exists. Always, unless
     *         overridden. Asked before {@link #aim(Vec3, Object)} for the same spot
     */
    default boolean clickable(Vec3 eye, S spot) {
        return true;
    }

    /** @return where you would look from {@code eye} to place at {@code spot}: {@link #aim(Object)} unless overridden */
    default Vec3 aim(Vec3 eye, S spot) {
        return aim(spot);
    }

    /**
     * @return whether placing sets it off in the same tick, as with a bed placed
     *         and used at once: what has already gone off this tick then counts
     *         against it. Not unless overridden: a crystal goes off when broken.
     */
    default boolean firesAtOnce() {
        return false;
    }
}
