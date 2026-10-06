package dev.px.combat.search.option;

import dev.px.combat.place.Click;
import dev.px.core.math.Vec3i;

/**
 * Where to place a respawn anchor, charge it and set it off, and why.
 *
 * <p>Place it into {@link #getCell()}, use a glowstone block on it, then use it
 * with something that does not charge it: three clicks, each with the item your
 * module chooses. With {@code Clicks}, {@link #getClick()} places it, and
 * {@link #getUseClick()} is the click on the anchor itself that charges it and
 * sets it off.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class AnchorPlaceOption<E> extends Option<E> {

    private final Vec3i cell;
    private final Click click;
    private final Click useClick;

    /**
     * @param click how to click to place it; null when there are no click rules
     * @param useClick how to click it once placed; null when there are no click rules, or none was looked for
     */
    public AnchorPlaceOption(Vec3i cell, Option<E> found, Click click, Click useClick) {
        super(found);
        this.cell = cell;
        this.click = click;
        this.useClick = useClick;
    }

    /** @return the cell to place the anchor into */
    public Vec3i getCell() {
        return cell;
    }

    /**
     * @return how to click to place it, when the search was given {@code Clicks}:
     *         the block, the face and the hit vector your server accepts, and what
     *         {@link #getAim()} points at. Null without them
     */
    public Click getClick() {
        return click;
    }

    /**
     * @return how to click the anchor once it is placed, to charge it and to set
     *         it off. Null without {@code Clicks}, and when the search places in one
     *         tick and the next ones charge and set it off: {@code findUse} then
     *         finds it standing
     */
    public Click getUseClick() {
        return useClick;
    }

    @Override
    public String toString() {
        return "AnchorPlaceOption(" + cell + ": " + facts() + ")";
    }
}
