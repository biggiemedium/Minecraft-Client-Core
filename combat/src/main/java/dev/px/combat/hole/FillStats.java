package dev.px.combat.hole;

import java.util.Locale;

/**
 * What one fill search did: how many holes it found, and why each one it left
 * out was left out. For profiling, and for seeing why nothing was filled.
 *
 * <p>A snapshot; {@link HoleFill} fills a new one per search.
 */
public final class FillStats {

    int found;
    int inReach;
    int escape;
    int own;
    int friends;
    int pending;
    int unclickable;
    int unthreatened;
    int entered;
    int occupied;
    int unlikely;
    int filtered;
    int offered;

    FillStats() {
    }

    /** @return holes found near enough to look at: to just past place range */
    public int getFound() {
        return found;
    }

    /** @return of those, with every cell in reach */
    public int getInReach() {
        return inReach;
    }

    /** @return kept for you to escape into: within your escape radius */
    public int getEscape() {
        return escape;
    }

    /** @return kept because you are in it, or likely heading for it */
    public int getOwn() {
        return own;
    }

    /** @return kept because someone you protect is in it, or likely heading for it */
    public int getFriends() {
        return friends;
    }

    /** @return skipped because you filled it and the server has not shown it yet */
    public int getPending() {
        return pending;
    }

    /** @return with a cell no click your rules accept reaches */
    public int getUnclickable() {
        return unclickable;
    }

    /** @return that no enemy could reach within the horizon */
    public int getUnthreatened() {
        return unthreatened;
    }

    /** @return that an enemy is already in: too late */
    public int getEntered() {
        return entered;
    }

    /** @return with someone else in it */
    public int getOccupied() {
        return occupied;
    }

    /** @return below the minimum chance for every enemy that could reach it */
    public int getUnlikely() {
        return unlikely;
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
        return String.format(Locale.ROOT,
                "FillStats(found %d, in reach %d, escape %d, own %d, friends %d, pending %d, unclickable %d, "
                        + "unthreatened %d, entered %d, occupied %d, unlikely %d, filtered %d, offered %d)",
                found, inReach, escape, own, friends, pending, unclickable, unthreatened, entered, occupied, unlikely,
                filtered, offered);
    }
}
