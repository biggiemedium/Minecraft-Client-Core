package dev.px.combat.search.option;

import dev.px.combat.bed.Bed;
import dev.px.combat.bed.BedPart;
import dev.px.combat.place.Click;
import dev.px.core.math.Vec3i;

import java.util.Locale;

/**
 * Which bed already in the world to use, which half to click, and why.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class BedUseOption<E> extends Option<E> {

    private final Bed bed;
    private final BedPart part;
    private final Click click;

    public BedUseOption(Bed bed, BedPart part, Option<E> found) {
        this(bed, part, found, null);
    }

    /** @param click how to click the half to use; null when there are no click rules */
    public BedUseOption(Bed bed, BedPart part, Option<E> found, Click click) {
        super(found);
        this.bed = bed;
        this.part = part;
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

    /** @return the half to use: one in reach, the nearer if both are */
    public BedPart getPart() {
        return part;
    }

    /** @return the cell to click */
    public Vec3i getCell() {
        return bed.get(part);
    }

    @Override
    public String toString() {
        return "BedUseOption(" + bed + ", using the " + part.name().toLowerCase(Locale.ROOT)
                + ": " + facts() + ")";
    }
}
