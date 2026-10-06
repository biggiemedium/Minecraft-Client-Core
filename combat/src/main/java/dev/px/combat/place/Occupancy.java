package dev.px.combat.place;

import dev.px.core.math.Vec3i;
import dev.px.core.movement.prediction.Future;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which cells an entity is likely to be in over a stretch of ticks, from every
 * one of its predicted futures, not just the likeliest: the chance of each, and
 * the first tick it would be there.
 *
 * <pre>{@code
 * Prediction next = Core.prediction().predict(enemy, 10);
 * Occupancy path = Occupancy.of(next, landTicks, landTicks + 4);
 *
 * path.cells(0.3)                 // web where they are likely to run into, nearest on their path first
 * path.chance(cell) > 0.1         // a solid block landing there would likely be refused: they are in it
 * }</pre>
 *
 * <p>A future counts once per cell, with its weight, at the first tick its box is
 * in that cell, so a cell's chance is the share of belief that the entity passes
 * through it at all within the stretch. The box is the entity's own size.
 *
 * <p>Immutable.
 */
public final class Occupancy {

    /** One cell: how likely it is to be entered within the stretch, and how soon. */
    public static final class Visit {
        private final Vec3i cell;
        private final double chance;
        private final int firstTick;

        Visit(Vec3i cell, double chance, int firstTick) {
            this.cell = cell;
            this.chance = chance;
            this.firstTick = firstTick;
        }

        public Vec3i getCell() {
            return cell;
        }

        /** @return the share of belief, 0 to 1, that it is in this cell at some tick of the stretch */
        public double getChance() {
            return chance;
        }

        /** @return the soonest tick, from now, any future has it in this cell */
        public int getFirstTick() {
            return firstTick;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "Visit(%s, %.2f at %d)", cell, chance, firstTick);
        }
    }

    private final Map<Vec3i, Visit> visits;

    private Occupancy(Map<Vec3i, Visit> visits) {
        this.visits = visits;
    }

    /**
     * @param from the first tick of the stretch, from now; 0 is now
     * @param to   the last, at most the prediction's ticks
     */
    public static Occupancy of(Prediction prediction, int from, int to) {
        Validate.notNull(prediction, "prediction");
        Validate.check(from >= 0 && from <= to, "the stretch must run forward from now");
        int last = Math.min(to, prediction.getTicks());
        double width = prediction.getEntity().getWidth();
        double height = prediction.getEntity().getHeight();
        Map<Vec3i, double[]> seen = new LinkedHashMap<>();
        for (Future future : prediction.getFutures()) {
            Map<Vec3i, Integer> firstHere = new LinkedHashMap<>();
            for (int tick = Math.min(from, last); tick <= last; tick++) {
                MotionState state = future.at(tick);
                for (Vec3i cell : Shapes.occupied(state.hitbox(width, height))) {
                    if (!firstHere.containsKey(cell)) {
                        firstHere.put(cell, tick);
                    }
                }
            }
            for (Map.Entry<Vec3i, Integer> entry : firstHere.entrySet()) {
                double[] totals = seen.get(entry.getKey());
                if (totals == null) {
                    totals = new double[] { 0d, Double.POSITIVE_INFINITY };
                    seen.put(entry.getKey(), totals);
                }
                totals[0] += future.getWeight();
                totals[1] = Math.min(totals[1], entry.getValue());
            }
        }
        Map<Vec3i, Visit> visits = new LinkedHashMap<>();
        for (Map.Entry<Vec3i, double[]> entry : seen.entrySet()) {
            visits.put(entry.getKey(), new Visit(entry.getKey(), Math.min(1d, entry.getValue()[0]),
                    (int) entry.getValue()[1]));
        }
        return new Occupancy(visits);
    }

    /** @return the chance it is in {@code cell} at some tick of the stretch; 0 for a cell no future enters */
    public double chance(Vec3i cell) {
        Visit visit = visits.get(cell);
        return visit == null ? 0d : visit.getChance();
    }

    /** @return the cell's visit, or null when no future enters it */
    public Visit visit(Vec3i cell) {
        return visits.get(cell);
    }

    /** @return every cell entered with at least {@code chance}, soonest first, then likeliest */
    public List<Visit> cells(double chance) {
        List<Visit> likely = new ArrayList<>();
        for (Visit visit : visits.values()) {
            if (visit.getChance() >= chance) {
                likely.add(visit);
            }
        }
        likely.sort((a, b) -> a.getFirstTick() != b.getFirstTick()
                ? Integer.compare(a.getFirstTick(), b.getFirstTick()) : Double.compare(b.getChance(), a.getChance()));
        return Collections.unmodifiableList(likely);
    }

    /** @return the cells of {@link #cells(double)}, as cells, for a planner */
    public List<Vec3i> likelyCells(double chance) {
        List<Vec3i> cells = new ArrayList<>();
        for (Visit visit : cells(chance)) {
            cells.add(visit.getCell());
        }
        return cells;
    }
}
