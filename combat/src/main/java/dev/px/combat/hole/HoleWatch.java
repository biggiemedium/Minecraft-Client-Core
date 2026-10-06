package dev.px.combat.hole;

import dev.px.core.entity.Tracked;
import dev.px.core.movement.prediction.Future;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.movement.prediction.Scenario;
import dev.px.core.util.Validate;
import dev.px.core.world.Obstructions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.ToDoubleFunction;

/**
 * Which holes an entity might get into, and when: what an auto-fill, a surround
 * breaker or a hole-aware crystal aura asks about an enemy, or about a friend
 * whose hole you must not fill.
 *
 * <pre>{@code
 * HoleWatch watch = HoleWatch.builder()
 *         .finder(new HoleFinder(holeRules))
 *         .prediction(Core.prediction())
 *         .obstructions(Obstructions.of(Core.entities(), players))  // someone else in a hole fills it
 *         .horizon(() -> pingTicks() + placeDelay.getInt())        // how far ahead your fill lands
 *         .radius(range::getDouble)
 *         .build();
 *
 * for (HoleEntry entry : watch.watch(enemy)) {
 *     if (!entry.isInside() && !entry.isOccupied()
 *             && entry.getChance() > 0.5 && entry.getEarliestPossible() > myFillTicks) {
 *         fill(entry.getHole());                                    // likely, and still in time
 *     }
 * }
 * }</pre>
 *
 * <h2>How</h2>
 *
 * <ol>
 *   <li>The holes within the radius of the entity are found, and kept only if it
 *       could possibly reach them within the horizon &mdash; at the fastest of its
 *       rules and what it has been seen to do. The soonest few are kept.
 *   <li>The entity is predicted once with a "heads for it" scenario per hole kept,
 *       alongside the prediction service's own. Each is weighed by how well it
 *       explains its last few ticks, so a hole it has been walking toward carries
 *       weight, and one it has been walking past does not.
 *   <li>Each hole's chance is the weight of the futures that end up in it.
 * </ol>
 *
 * <p><b>Heading for a hole looks like running over it.</b> Sprinting carries a
 * player straight over a one-block hole, so only a future that stops over it
 * drops in &mdash; and until they slow down, someone sprinting at a hole has moved
 * exactly like someone about to cross it. The evidence splits the chance between
 * them. {@link HoleEntry#getArrivalTick} says when they would be in if they go for
 * it, whatever the chance; and {@link Builder#prior} leans toward holes when you
 * believe players running at one usually mean it.
 *
 * <p>Two predictions per entity watched. Game thread only.
 */
public final class HoleWatch {

    /** Holes considered per entity unless {@link Builder#maxHoles} says otherwise: the soonest possible first. */
    public static final int DEFAULT_MAX_HOLES = 6;

    private final HoleFinder finder;
    private final PredictionService prediction;
    private final Obstructions obstructions;
    private final IntSupplier horizon;
    private final DoubleSupplier radius;
    private final ToDoubleFunction<Tracked<?>> prior;
    private final int maxHoles;

    private HoleWatch(Builder builder) {
        this.finder = builder.finder;
        this.prediction = builder.prediction;
        this.obstructions = builder.obstructions;
        this.horizon = builder.horizon;
        this.radius = builder.radius;
        this.prior = builder.prior;
        this.maxHoles = builder.maxHoles;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the holes near {@code entity} it could reach within the horizon, the
     *         one it is in first, then by likely tick, then by chance; empty when
     *         there are none
     */
    public List<HoleEntry> watch(Tracked<?> entity) {
        Validate.notNull(entity, "entity");
        return assess(entity, finder.around(entity.getPosition(), radius.getAsDouble()));
    }

    /**
     * {@link #watch}, about holes you found yourself: around you, say, rather than
     * around {@code entity}.
     *
     * @return those of {@code holes} it could reach within the horizon, as
     *         {@link #watch} orders them; empty when there are none
     */
    public List<HoleEntry> assess(Tracked<?> entity, Collection<Hole> holes) {
        Validate.notNull(entity, "entity");
        Validate.notNull(holes, "holes");
        int ticks = Math.max(1, horizon.getAsInt());
        List<Hole> near = new ArrayList<>(holes);
        if (near.isEmpty()) {
            return Collections.emptyList();
        }
        Prediction first = prediction.predict(entity, ticks);
        List<Candidate> reachable = new ArrayList<>();
        for (Hole hole : near) {
            int possible = hole.contains(entity) ? 0 : first.earliestPossible(hole.getBox());
            if (possible <= ticks) {
                reachable.add(new Candidate(hole, possible));
            }
        }
        reachable.sort((a, b) -> Integer.compare(a.possible, b.possible));
        if (reachable.size() > maxHoles) {
            reachable = new ArrayList<>(reachable.subList(0, maxHoles));
        }
        if (reachable.isEmpty()) {
            return Collections.emptyList();
        }

        Scenario[] heading = new Scenario[reachable.size()];
        double lean = prior.applyAsDouble(entity);
        for (int i = 0; i < heading.length; i++) {
            Hole hole = reachable.get(i).hole;
            Scenario toward = Scenario.toward("into " + hole, hole.getCentre());
            heading[i] = lean == 1d ? toward : toward.withPrior(lean);
        }
        Prediction futures = prediction.predict(entity, ticks, heading);
        double width = entity.getWidth();
        List<HoleEntry> entries = new ArrayList<>(reachable.size());
        for (int i = 0; i < reachable.size(); i++) {
            Candidate candidate = reachable.get(i);
            Hole hole = candidate.hole;
            int arrival = -1;
            for (Future future : futures.getFutures()) {
                if (future.getName().equals(heading[i].getName())) {
                    arrival = future.firstTick(state -> hole.contains(state, width));
                }
            }
            boolean inside = hole.contains(entity);
            double chance = futures.chanceBy(state -> hole.contains(state, width), ticks);
            int likely = -1;
            for (int tick = 0; tick <= ticks; tick++) {
                if (futures.chanceBy(state -> hole.contains(state, width), tick) > 0.5d) {
                    likely = tick;
                    break;
                }
            }
            boolean occupied = !inside && obstructions.any(hole.getBox());
            entries.add(new HoleEntry(entity, hole, inside, occupied, chance, likely, candidate.possible,
                    inside ? 0 : arrival, futures));
        }
        entries.sort((a, b) -> {
            if (a.isInside() != b.isInside()) {
                return a.isInside() ? -1 : 1;
            }
            int aTick = a.getLikelyTick() < 0 ? Integer.MAX_VALUE : a.getLikelyTick();
            int bTick = b.getLikelyTick() < 0 ? Integer.MAX_VALUE : b.getLikelyTick();
            if (aTick != bTick) {
                return Integer.compare(aTick, bTick);
            }
            return Double.compare(b.getChance(), a.getChance());
        });
        return entries;
    }

    private static final class Candidate {
        final Hole hole;
        final int possible;

        Candidate(Hole hole, int possible) {
            this.hole = hole;
            this.possible = possible;
        }
    }

    public static final class Builder {

        private HoleFinder finder;
        private PredictionService prediction;
        private Obstructions obstructions = Obstructions.NONE;
        private IntSupplier horizon;
        private DoubleSupplier radius;
        private ToDoubleFunction<Tracked<?>> prior = entity -> 1d;
        private int maxHoles = DEFAULT_MAX_HOLES;

        private Builder() {
        }

        /** Required: how holes are found. */
        public Builder finder(HoleFinder finder) {
            this.finder = Validate.notNull(finder, "finder");
            return this;
        }

        /** Required: how entities are predicted; {@code Core.prediction()}. */
        public Builder prediction(PredictionService prediction) {
            this.prediction = Validate.notNull(prediction, "prediction");
            return this;
        }

        /**
         * What counts as someone else in a hole. Nothing unless set. The local
         * player counts if your obstructions see them, so a hole you stand in is
         * occupied.
         */
        public Builder obstructions(Obstructions obstructions) {
            this.obstructions = Validate.notNull(obstructions, "obstructions");
            return this;
        }

        /**
         * Required: how many ticks ahead to look, read live: until whatever you do
         * about it lands. Keep it at least three under the history your trackers
         * keep: futures are weighed by playing them from that far back, and with
         * too little history they all weigh the same.
         */
        public Builder horizon(IntSupplier ticks) {
            this.horizon = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** Required: how far around the entity to look for holes, in blocks, read live. */
        public Builder radius(DoubleSupplier blocks) {
            this.radius = Validate.notNull(blocks, "blocks");
            return this;
        }

        /**
         * How much more a future heading into a hole is believed than any other,
         * before the evidence: 1, no lean, unless set. Read live. See the class notes
         * on why the evidence alone cannot always tell.
         */
        public Builder prior(DoubleSupplier prior) {
            Validate.notNull(prior, "prior");
            this.prior = entity -> prior.getAsDouble();
            return this;
        }

        /**
         * {@link #prior(DoubleSupplier)}, decided per entity: lean further toward
         * holes for someone low on health, say, who is likelier to run for one.
         */
        public Builder priorOf(ToDoubleFunction<Tracked<?>> prior) {
            this.prior = Validate.notNull(prior, "prior");
            return this;
        }

        /** @param holes how many holes to consider per entity: the soonest it could reach first */
        public Builder maxHoles(int holes) {
            Validate.check(holes >= 1, "maxHoles must be at least 1");
            this.maxHoles = holes;
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public HoleWatch build() {
            StringBuilder missing = new StringBuilder();
            if (finder == null) {
                missing.append(" finder");
            }
            if (prediction == null) {
                missing.append(" prediction");
            }
            if (horizon == null) {
                missing.append(" horizon");
            }
            if (radius == null) {
                missing.append(" radius");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("a HoleWatch needs:" + missing);
            }
            return new HoleWatch(this);
        }
    }
}
