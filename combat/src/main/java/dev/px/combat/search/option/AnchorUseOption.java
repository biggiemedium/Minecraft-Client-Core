package dev.px.combat.search.option;

import dev.px.combat.anchor.Anchor;
import dev.px.combat.place.Click;
import dev.px.core.math.Vec3i;

/**
 * Which respawn anchor already in the world to set off, and why.
 *
 * <p>An empty one needs a charge first: {@link #getChargesNeeded()} says how many
 * glowstone clicks come before the click that sets it off.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class AnchorUseOption<E> extends Option<E> {

    private final Anchor anchor;
    private final Click click;

    /** @param click how to click it; null when there are no click rules */
    public AnchorUseOption(Anchor anchor, Option<E> found, Click click) {
        super(found);
        this.anchor = anchor;
        this.click = click;
    }

    public Anchor getAnchor() {
        return anchor;
    }

    /** @return the cell to click */
    public Vec3i getCell() {
        return anchor.getCell();
    }

    /** @return glowstone clicks to make before the one that sets it off: 0 when it is charged already */
    public int getChargesNeeded() {
        return anchor.isCharged() ? 0 : 1;
    }

    /**
     * @return how to click it, when the search was given {@code Clicks}: the
     *         block, the face and the hit vector your server accepts, and what
     *         {@link #getAim()} points at. Null without them
     */
    public Click getClick() {
        return click;
    }

    @Override
    public String toString() {
        return "AnchorUseOption(" + anchor + ": " + facts() + ")";
    }
}
