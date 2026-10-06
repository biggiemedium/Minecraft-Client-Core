package dev.px.combat.trap;

import java.util.Locale;

/**
 * What one trap search did: who it looked at, and why each one it left out was
 * left out.
 *
 * <p>A snapshot; {@link TrapSearch} fills a new one per search.
 */
public final class TrapStats {

    int targets;
    int moving;
    int sealed;
    int unplaceable;
    int filtered;
    int offered;

    TrapStats() {
    }

    /** @return targets looked at */
    public int getTargets() {
        return targets;
    }

    /** @return left alone because they are moving, or not seen long enough to tell, and trapping moving targets is off */
    public int getMoving() {
        return moving;
    }

    /** @return already trapped: every cell filled */
    public int getSealed() {
        return sealed;
    }

    /** @return with cells still to fill, none of which can be placed this tick */
    public int getUnplaceable() {
        return unplaceable;
    }

    /** @return refused by your filters */
    public int getFiltered() {
        return filtered;
    }

    /** @return offered, before the cut to however many were asked for */
    public int getOffered() {
        return offered;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "TrapStats(targets %d, moving %d, sealed %d, unplaceable %d, filtered %d, offered %d)",
                targets, moving, sealed, unplaceable, filtered, offered);
    }
}
