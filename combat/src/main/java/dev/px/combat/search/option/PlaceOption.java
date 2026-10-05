package dev.px.combat.search.option;

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

    public PlaceOption(int x, int y, int z, Option<E> found) {
        super(found);
        this.x = x;
        this.y = y;
        this.z = z;
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
