package dev.px.combat.search.option;

import dev.px.combat.place.Click;

/**
 * Where to place a crystal, and why: the base block, who it is for, and what it
 * would do to them and to you.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class PlaceOption<E> extends Option<E> {

    private final int x;
    private final int y;
    private final int z;
    private final Click click;

    public PlaceOption(int x, int y, int z, Option<E> found) {
        this(x, y, z, found, null);
    }

    /** @param click how to click the base for it; null when there are no click rules */
    public PlaceOption(int x, int y, int z, Option<E> found, Click click) {
        super(found);
        this.x = x;
        this.y = y;
        this.z = z;
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


    /** @return the base block's x: place the crystal on top of it */
    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    @Override
    public String toString() {
        return "PlaceOption(" + x + ", " + y + ", " + z + ": " + facts() + ")";
    }
}
