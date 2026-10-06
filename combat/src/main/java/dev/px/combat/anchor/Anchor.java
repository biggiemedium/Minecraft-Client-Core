package dev.px.combat.anchor;

import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

/**
 * A respawn anchor in the world: its cell, and how many charges it holds.
 *
 * <p>Two anchors are equal when they are in the same cell with the same charges;
 * a search remembers one by its cell alone, so charging it does not make it a
 * different anchor to inhibit.
 *
 * <p>Immutable.
 */
public final class Anchor {

    private final Vec3i cell;
    private final int charges;

    private Anchor(Vec3i cell, int charges) {
        this.cell = cell;
        this.charges = charges;
    }

    /** @param charges how many it holds, as your lookup reads them; 0 for none */
    public static Anchor of(Vec3i cell, int charges) {
        Validate.notNull(cell, "cell");
        Validate.check(charges >= 0, "charges must not be negative");
        return new Anchor(cell, charges);
    }

    public Vec3i getCell() {
        return cell;
    }

    public int getCharges() {
        return charges;
    }

    /** @return whether it holds a charge: what using it outside the dimensions it works in needs to explode */
    public boolean isCharged() {
        return charges > 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Anchor)) {
            return false;
        }
        Anchor anchor = (Anchor) other;
        return cell.equals(anchor.cell) && charges == anchor.charges;
    }

    @Override
    public int hashCode() {
        return 31 * cell.hashCode() + charges;
    }

    @Override
    public String toString() {
        return "Anchor(" + cell + ", " + charges + (charges == 1 ? " charge)" : " charges)");
    }
}
