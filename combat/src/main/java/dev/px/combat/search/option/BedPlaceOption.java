package dev.px.combat.search.option;

import dev.px.combat.bed.Bed;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3i;

/**
 * Where to place a bed and set it off, and why.
 *
 * <p>Place it with the foot in {@link #getFoot()}, facing {@link #getFacing()}
 * &mdash; {@code getFacing().toYaw()} is the yaw to send &mdash; then use it at
 * once. Which face to click to put the foot there is your client's business.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class BedPlaceOption<E> extends Option<E> {

    private final Bed bed;

    public BedPlaceOption(Bed bed, Option<E> found) {
        super(found);
        this.bed = bed;
    }

    public Bed getBed() {
        return bed;
    }

    /** @return the cell to place into: the foot goes here */
    public Vec3i getFoot() {
        return bed.getFoot();
    }

    public Vec3i getHead() {
        return bed.getHead();
    }

    /** @return the way to face while placing */
    public Direction getFacing() {
        return bed.getFacing();
    }

    @Override
    public String toString() {
        return "BedPlaceOption(" + bed + ": " + facts() + ")";
    }
}
