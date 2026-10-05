package dev.px.combat.search.engine;

import java.util.Locale;

/**
 * What one search did: how many cells and explosives it looked at, how many it
 * threw out at each step, and how many raycast estimates it paid for.
 *
 * <p>For profiling, and for seeing why nothing was found: a search with every
 * spot out of reach looks very different from one where every spot was too weak.
 *
 * <p>A snapshot; {@link ExplosiveSearch} fills a new one per search.
 */
public final class SearchStats {

    int cells;
    int inReach;
    int placeable;
    int visible;
    int bounds;
    int viable;
    int pruned;
    int evaluated;
    int selfEvaluated;
    int protectedEvaluated;
    int endangering;
    int filtered;
    int existing;
    int inhibited;
    int tooYoung;

    SearchStats() {
    }

    /** @return grid cells the placement scan visited */
    public int getCells() {
        return cells;
    }

    /** @return cells within place range */
    public int getInReach() {
        return inReach;
    }

    /** @return spots something can be placed in now: a base for a crystal, a cell and facing for a bed */
    public int getPlaceable() {
        return placeable;
    }

    /** @return of those, ones within wall range or visible */
    public int getVisible() {
        return visible;
    }

    /** @return upper bounds worked out: no raycasting, one per candidate and target in range */
    public int getBounds() {
        return bounds;
    }

    /** @return candidates whose upper bound could meet some target's threshold */
    public int getViable() {
        return viable;
    }

    /** @return viable candidates never estimated, because their bound could not beat the best found */
    public int getPruned() {
        return pruned;
    }

    /** @return exact damage estimates against targets: the raycasting cost */
    public int getEvaluated() {
        return evaluated;
    }

    /** @return exact damage estimates against yourself */
    public int getSelfEvaluated() {
        return selfEvaluated;
    }

    /** @return exact damage estimates against entities the search protects */
    public int getProtectedEvaluated() {
        return protectedEvaluated;
    }

    /** @return spots or explosives refused because they would hurt someone protected too much */
    public int getEndangering() {
        return endangering;
    }

    /** @return options your {@code OptionFilter}s refused */
    public int getFiltered() {
        return filtered;
    }

    /** @return explosives already in the world the search looked at: crystals, beds */
    public int getExisting() {
        return existing;
    }

    /** @return of those, skipped because you set them off too recently */
    public int getInhibited() {
        return inhibited;
    }

    /** @return of those, skipped for not having existed long enough */
    public int getTooYoung() {
        return tooYoung;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "SearchStats(cells %d, in reach %d, placeable %d, visible %d, viable %d, pruned %d, "
                        + "estimates %d + %d self + %d protected, endangering %d, filtered %d, "
                        + "existing %d, inhibited %d, too young %d)",
                cells, inReach, placeable, visible, viable, pruned, evaluated, selfEvaluated, protectedEvaluated,
                endangering, filtered, existing, inhibited, tooYoung);
    }
}
