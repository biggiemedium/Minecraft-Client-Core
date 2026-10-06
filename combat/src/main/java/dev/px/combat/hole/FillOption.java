package dev.px.combat.hole;

import dev.px.combat.place.Click;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3i;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A hole worth filling, and why: who it would be denied to, when they would get
 * there, and whether your fill gets there first. What to fill it with is yours.
 *
 * <pre>{@code
 * for (FillOption<LivingEntity> fill : search.findFills(2)) {
 *     if (fill.getTiming() == FillOption.Timing.LATE) continue;          // or try anyway: yours to say
 *     Plan plan = planner.plan(eye, fill.getCells(), looking);          // obsidian, webs, whatever you hold
 *     place(plan);
 *     search.filled(fill);
 * }
 * }</pre>
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for who is denied
 */
public final class FillOption<E> {

    /** Whether your fill lands before they get there. */
    public enum Timing {

        /** Before they could possibly get there, however they move: they cannot beat it. */
        SAFE,

        /** Before they likely get there, but they could beat it by moving as fast as they ever have. */
        RACE,

        /** After they likely get there: they will probably be in first. */
        LATE
    }

    private final Hole hole;
    private final List<Vec3i> cells;
    private final List<Click> clicks;
    private final Tracked<? extends E> enemy;
    private final HoleEntry entry;
    private final Timing timing;
    private final int slack;
    private final int threats;

    FillOption(Hole hole, List<Vec3i> cells, List<Click> clicks, Tracked<? extends E> enemy, HoleEntry entry,
               Timing timing, int slack, int threats) {
        this.hole = hole;
        this.cells = Collections.unmodifiableList(cells);
        this.clicks = clicks == null ? null : Collections.unmodifiableList(clicks);
        this.enemy = enemy;
        this.entry = entry;
        this.timing = timing;
        this.slack = slack;
        this.threats = threats;
    }

    public Hole getHole() {
        return hole;
    }

    /** @return the cells to fill, the one the enemy comes to first first: all of them, so no smaller hole is left */
    public List<Vec3i> getCells() {
        return cells;
    }

    /**
     * @return how to click each cell, in the same order, when the search was given
     *         {@code Clicks}; null without them
     */
    public List<Click> getClicks() {
        return clicks;
    }

    /** @return the enemy it is denied to: the one who would get there soonest */
    public Tracked<? extends E> getEnemy() {
        return enemy;
    }

    /** @return how many enemies could get there within the horizon */
    public int getThreats() {
        return threats;
    }

    /** @return that enemy and this hole, as {@code HoleWatch} saw them */
    public HoleEntry getEntry() {
        return entry;
    }

    /** @return the share of the enemy's predicted futures that end up in it */
    public double getChance() {
        return entry.getChance();
    }

    /** @return when more of its futures are in it than not; -1 if not within the horizon */
    public int getLikelyTick() {
        return entry.getLikelyTick();
    }

    /** @return when it would be in it, if it heads for it at the pace it moves now; -1 if not within the horizon */
    public int getArrivalTick() {
        return entry.getArrivalTick();
    }

    /** @return the soonest it could possibly be in it */
    public int getEarliestPossible() {
        return entry.getEarliestPossible();
    }

    /** @return whether your fill lands first */
    public Timing getTiming() {
        return timing;
    }

    /**
     * @return ticks your fill could still wait and land before the enemy could
     *         possibly get there; negative when it is already too late to be sure
     */
    public int getSlack() {
        return slack;
    }

    /** @return whether the enemy's prediction could be trusted; when not, lean on {@link #getEarliestPossible()} */
    public boolean isReliable() {
        return entry.getPrediction().isReliable();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "FillOption(%s, %s, chance %.2f, likely %d, arrives %d, possible %d, slack %d)",
                hole, timing, getChance(), getLikelyTick(), getArrivalTick(), getEarliestPossible(), slack);
    }
}
