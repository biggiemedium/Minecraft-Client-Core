package dev.px.combat.search.option;

import dev.px.combat.bed.Bed;
import dev.px.combat.place.Click;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3i;

/**
 * Where to place a bed and set it off, and why.
 *
 * <p>Place it with the foot in {@link #getFoot()}, facing {@link #getFacing()}
 * &mdash; {@code getFacing().toYaw()} is the yaw to send &mdash; then use it at
 * once. With {@code Clicks}, {@link #getClick()} says what to click, and looking
 * at its hit faces the bed the right way.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class BedPlaceOption<E> extends Option<E> {

    private final Bed bed;
    private final Click click;

    public BedPlaceOption(Bed bed, Option<E> found) {
        this(bed, found, null);
    }

    /** @param click how to click to place it; null when there are no click rules */
    public BedPlaceOption(Bed bed, Option<E> found, Click click) {
        super(found);
        this.bed = bed;
        this.click = click;
    }
    /**
     * @return how to click for it, when the search was given {@code Clicks}: the
     *         block, the face and the hit vector your server accepts, and what
     *         {@link #getAim()} points at. Null without them
     */
    public Click getClick() {
        return click;
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
