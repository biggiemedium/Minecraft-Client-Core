package dev.px.combat.hole;

/** The footprint of a hole, in cells: what {@link HoleFinder} looks for. */
public enum HoleShape {

    /** One cell, walled on all four sides. */
    SINGLE(1, 1),

    /** Two cells side by side, along x or z, walled all round. */
    DOUBLE(2, 1),

    /** Two by two cells, walled all round. */
    QUAD(2, 2);

    private final int length;
    private final int width;

    HoleShape(int length, int width) {
        this.length = length;
        this.width = width;
    }

    /** @return how many cells a hole of this shape has */
    public int getCells() {
        return length * width;
    }
}
