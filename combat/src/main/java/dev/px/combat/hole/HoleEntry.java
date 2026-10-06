package dev.px.combat.hole;

import dev.px.core.entity.Tracked;
import dev.px.core.movement.prediction.Prediction;

import java.util.Locale;

/**
 * One hole near one entity, and when it might be in it: the likely answer from
 * its predicted futures, the possible answer from how fast it could get there at
 * all, whether it is in already, and whether someone else is.
 *
 * <p>What to do with it is your module's: filling a hole an enemy is likely to
 * reach, while there is still time before it possibly can, is one policy among
 * several.
 *
 * <p>Immutable.
 */
public final class HoleEntry {

    private final Tracked<?> entity;
    private final Hole hole;
    private final boolean inside;
    private final boolean occupied;
    private final double chance;
    private final int likelyTick;
    private final int earliestPossible;
    private final int arrivalTick;
    private final Prediction prediction;

    HoleEntry(Tracked<?> entity, Hole hole, boolean inside, boolean occupied, double chance, int likelyTick,
              int earliestPossible, int arrivalTick, Prediction prediction) {
        this.entity = entity;
        this.hole = hole;
        this.inside = inside;
        this.occupied = occupied;
        this.chance = chance;
        this.likelyTick = likelyTick;
        this.earliestPossible = earliestPossible;
        this.arrivalTick = arrivalTick;
        this.prediction = prediction;
    }

    public Tracked<?> getEntity() {
        return entity;
    }

    public Hole getHole() {
        return hole;
    }

    /** @return whether it is in the hole now */
    public boolean isInside() {
        return inside;
    }

    /** @return whether something else is in the hole: another player, an item, whatever your obstructions see */
    public boolean isOccupied() {
        return occupied;
    }

    /** @return the share of its predicted futures, 0 to 1, in which it is in the hole within the horizon */
    public double getChance() {
        return chance;
    }

    /** @return the first tick by which it is more likely than not in the hole; -1 if never within the horizon */
    public int getLikelyTick() {
        return likelyTick;
    }

    /**
     * @return the soonest it could possibly be in the hole, however it moves:
     *         see {@code Prediction.earliestPossible}. A floor: it cannot be
     *         sooner, short of moving faster than it has yet been seen to
     */
    public int getEarliestPossible() {
        return earliestPossible;
    }

    /**
     * @return when it would be in the hole if it heads for it, at the pace it moves
     *         now: the timing a fill needs, whatever the chance it goes for it; -1
     *         if even that is not within the horizon
     */
    public int getArrivalTick() {
        return arrivalTick;
    }

    /** @return the prediction this was read from, every hole near it included */
    public Prediction getPrediction() {
        return prediction;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "HoleEntry(%s, %s%s, chance %.2f, likely %d, arrives %d, possible %d)",
                hole, inside ? "inside" : "outside", occupied ? ", occupied" : "", chance, likelyTick, arrivalTick,
                earliestPossible);
    }
}
