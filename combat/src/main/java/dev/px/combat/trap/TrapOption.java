package dev.px.combat.trap;

import dev.px.combat.place.Plan;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.prediction.Prediction;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Trapping one player: the trap's cells, which are still to place, and this
 * tick's placements, the likeliest way out first.
 *
 * <pre>{@code
 * TrapOption<LivingEntity> trap = search.findTrap();
 * for (Plan.Step step : trap.getPlan().getSteps()) {
 *     turnTo(step.getClick().getRotation(eye));
 *     Game.interact(step.getClick());                 // with whatever block you hold
 * }
 * search.placed(trap);
 * }</pre>
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for who is trapped
 */
public final class TrapOption<E> {

    private final Tracked<? extends E> target;
    private final List<TrapPattern.Cell> cells;
    private final List<Vec3i> missing;
    private final List<Vec3i> deferred;
    private final Plan plan;
    private final boolean enclosed;
    private final boolean roofed;
    private final Prediction prediction;

    TrapOption(Tracked<? extends E> target, List<TrapPattern.Cell> cells, List<Vec3i> missing, List<Vec3i> deferred,
               Plan plan, boolean enclosed, boolean roofed, Prediction prediction) {
        this.target = target;
        this.cells = Collections.unmodifiableList(cells);
        this.missing = Collections.unmodifiableList(missing);
        this.deferred = Collections.unmodifiableList(deferred);
        this.plan = plan;
        this.enclosed = enclosed;
        this.roofed = roofed;
        this.prediction = prediction;
    }

    public Tracked<? extends E> getTarget() {
        return target;
    }

    /** @return the whole trap, in the order it is placed: the likeliest way out first */
    public List<TrapPattern.Cell> getCells() {
        return cells;
    }

    /** @return the trap's cells still to fill, in that order */
    public List<Vec3i> getMissing() {
        return missing;
    }

    /**
     * @return cells left out this tick because the target is likely in them when
     *         a block would land: a solid block there would be refused
     */
    public List<Vec3i> getDeferred() {
        return deferred;
    }

    /** @return this tick's placements, through your planner: supports, limits and clicks included */
    public Plan getPlan() {
        return plan;
    }

    /** @return whether every cell is filled: nothing left to place */
    public boolean isSealed() {
        return missing.isEmpty();
    }

    /** @return whether their feet are walled already, as in a hole: the only way out is up */
    public boolean isEnclosed() {
        return enclosed;
    }

    /** @return whether every roof cell is filled, or placed by this tick's plan: they cannot jump out */
    public boolean isRoofed() {
        return roofed;
    }

    /** @return the target's prediction this was planned from */
    public Prediction getPrediction() {
        return prediction;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "TrapOption(%s, %d missing, %d this tick%s%s)", target.getPosition(),
                missing.size(), plan.getSteps().size(), enclosed ? ", enclosed" : "", roofed ? ", roofed" : "");
    }
}
