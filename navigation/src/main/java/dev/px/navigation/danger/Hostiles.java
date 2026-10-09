package dev.px.navigation.danger;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Future;
import dev.px.core.movement.prediction.Lookahead;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Danger from hostiles, where they will be rather than where they are.
 *
 * <p>As a plan begins, the hostiles your {@link TargetSelector} picks are looked
 * ahead, tick by tick, over the {@linkplain Builder#horizon horizon}. A move then
 * costs more for every tick it ends near where one of them is expected to be
 * <em>at that tick</em>, so the planner goes round the zombie walking towards
 * the path, not round the spot it stood on when the plan was made.
 *
 * <pre>{@code
 * Hostiles<Mob> hostiles = Hostiles.<Mob>builder()
 *         .targets(Core.targets())
 *         .selector(mobs)                                             // who counts as hostile: your selector
 *         .predicted(Core.prediction())                               // where they will be
 *         .rule((mob, distance, tick) -> distance < 3 ? 20 : 0)      // how dangerous: your rule
 *         .build();
 *
 * LocalPlanner planner = LocalPlanner.builder().simulation(Core.simulation()).danger(hostiles).build();
 * }</pre>
 *
 * <p>Who is hostile, and how dangerous each is at what distance, are yours:
 * the selector and the {@link DangerRule}. A mob's reach is a fact about the game.
 *
 * <h2>Where they will be</h2>
 *
 * <p>{@linkplain Builder#predicted Predicted}, the usual choice, asks Core's
 * prediction once per hostile and weighs every future it gives by that future's
 * share of belief: a confident prediction counts almost only its likeliest
 * future, and an unsure one spreads the danger over each way the hostile might
 * go, which is the cautious answer when it cannot tell. A prediction further
 * ahead than the tracker keeps history for cannot be checked against it, so its
 * futures are weighed by their priors alone.
 *
 * <p>Any other {@link Lookahead} &mdash; a straight line, your own model &mdash; is
 * asked once per tick of the horizon and taken as certain.
 *
 * <p><b>Cost.</b> One prediction per hostile per plan, or one lookahead per
 * hostile per tick of the horizon. During the plan, each simulated tick costs one
 * distance per hostile per future.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for the hostiles
 */
public final class Hostiles<E> implements Danger {

    /** Ticks looked ahead by default. A tuning knob, not a game value. */
    public static final int DEFAULT_HORIZON = 40;

    /** Hostiles considered by default, the first your selector's sort ranks. */
    public static final int DEFAULT_LIMIT = 16;

    private final TargetService targets;
    private final TargetSelector<E> selector;
    private final PredictionService prediction;
    private final Lookahead<E> lookahead;
    private final DangerRule<E> rule;
    private final IntSupplier horizon;
    private final IntSupplier limit;

    private int lastCount;

    private Hostiles(Builder<E> builder) {
        this.targets = builder.targets;
        this.selector = builder.selector;
        this.prediction = builder.prediction;
        this.lookahead = builder.lookahead;
        this.rule = builder.rule;
        this.horizon = builder.horizon;
        this.limit = builder.limit;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** @return how many hostiles the last plan weighed */
    public int getLastCount() {
        return lastCount;
    }

    @Override
    public DangerField at(MotionState start, int ticks) {
        Validate.notNull(start, "start");
        List<Tracked<E>> found = targets.all(selector, start.getPosition(), Math.max(0, limit.getAsInt()));
        lastCount = found.size();
        if (found.isEmpty()) {
            return DangerField.NONE;
        }
        int ahead = Math.max(0, Math.min(ticks, horizon.getAsInt()));
        List<Expected<E>> expected = new ArrayList<>(found.size());
        for (Tracked<E> hostile : found) {
            if (prediction != null) {
                predict(hostile, ahead, expected);
                continue;
            }
            Box[] boxes = new Box[ahead + 1];
            for (int tick = 0; tick <= ahead; tick++) {
                Tracked<? extends E> there = lookahead.at(hostile, tick);
                boxes[tick] = (there != null ? there : hostile).getBox();
            }
            expected.add(new Expected<>(hostile, boxes, 1d));
        }
        return new Field<>(rule, expected);
    }

    /** Adds one entry per future the prediction gives, each weighed by its share of belief. */
    private void predict(Tracked<E> hostile, int ahead, List<Expected<E>> expected) {
        Box now = hostile.getBox();
        if (ahead == 0) {
            expected.add(new Expected<>(hostile, new Box[] {now}, 1d));
            return;
        }
        Vec3 at = hostile.getPosition();
        Prediction next = prediction.predict(hostile, ahead);
        for (Future future : next.getFutures()) {
            if (future.getWeight() <= 0d) {
                continue;
            }
            Box[] boxes = new Box[ahead + 1];
            for (int tick = 0; tick <= ahead; tick++) {
                Vec3 there = future.positionAt(Math.min(tick, future.getTicks()));
                boxes[tick] = now.offset(there.getX() - at.getX(), there.getY() - at.getY(), there.getZ() - at.getZ());
            }
            expected.add(new Expected<>(hostile, boxes, future.getWeight()));
        }
    }

    /** @return the gap between two boxes, 0 when they touch or overlap */
    static double distance(Box a, Box b) {
        double dx = Math.max(0d, Math.max(b.getMinX() - a.getMaxX(), a.getMinX() - b.getMaxX()));
        double dy = Math.max(0d, Math.max(b.getMinY() - a.getMaxY(), a.getMinY() - b.getMaxY()));
        double dz = Math.max(0d, Math.max(b.getMinZ() - a.getMaxZ(), a.getMinZ() - b.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** One way one hostile may go: its box at each tick looked ahead, and how much it is believed. */
    private static final class Expected<E> {

        private final Tracked<E> hostile;
        private final Box[] boxes;
        private final double weight;

        private Expected(Tracked<E> hostile, Box[] boxes, double weight) {
            this.hostile = hostile;
            this.boxes = boxes;
            this.weight = weight;
        }
    }

    private static final class Field<E> implements DangerField {

        private final DangerRule<E> rule;
        private final List<Expected<E>> expected;

        private Field(DangerRule<E> rule, List<Expected<E>> expected) {
            this.rule = rule;
            this.expected = expected;
        }

        @Override
        public double cost(Box player, int tick) {
            double total = 0d;
            for (int i = 0; i < expected.size(); i++) {
                Expected<E> one = expected.get(i);
                // Past the horizon, the last place looked ahead to stands for every later tick.
                Box box = one.boxes[Math.min(Math.max(tick, 0), one.boxes.length - 1)];
                double cost = rule.cost(one.hostile, distance(player, box), tick);
                if (cost > 0d) {
                    total += cost * one.weight;
                }
            }
            return total;
        }
    }

    /**
     * Builds {@link Hostiles}.
     *
     * <p>Needs the targets to search, a selector, where they will be
     * ({@link #predicted} or a {@link #lookahead}) and a {@link DangerRule}.
     */
    public static final class Builder<E> {

        private TargetService targets;
        private TargetSelector<E> selector;
        private PredictionService prediction;
        private Lookahead<E> lookahead;
        private DangerRule<E> rule;
        private IntSupplier horizon = () -> DEFAULT_HORIZON;
        private IntSupplier limit = () -> DEFAULT_LIMIT;

        private Builder() {
        }

        /** Where hostiles are found: usually {@code Core.targets()}. */
        public Builder<E> targets(TargetService targets) {
            this.targets = Validate.notNull(targets, "targets");
            return this;
        }

        /** Who counts as hostile, and which first when there are more than the {@linkplain #limit limit}. */
        public Builder<E> selector(TargetSelector<E> selector) {
            this.selector = Validate.notNull(selector, "selector");
            return this;
        }

        /**
         * Where each hostile will be, by Core's prediction: once per hostile per
         * plan, every future weighed by its share of belief. Replaces a
         * {@link #lookahead}.
         */
        public Builder<E> predicted(PredictionService prediction) {
            this.prediction = Validate.notNull(prediction, "prediction");
            this.lookahead = null;
            return this;
        }

        /**
         * Where each hostile will be by any {@link Lookahead}, asked once per tick
         * of the horizon and taken as certain. Replaces {@link #predicted}.
         */
        public Builder<E> lookahead(Lookahead<E> lookahead) {
            this.lookahead = Validate.notNull(lookahead, "lookahead");
            this.prediction = null;
            return this;
        }

        /** How dangerous each hostile is at each distance. */
        public Builder<E> rule(DangerRule<E> rule) {
            this.rule = Validate.notNull(rule, "rule");
            return this;
        }

        /**
         * How many ticks to look each hostile ahead, read as each plan begins.
         * Past it, a hostile stays where it was expected to be at the horizon.
         */
        public Builder<E> horizon(IntSupplier ticks) {
            this.horizon = Validate.notNull(ticks, "ticks");
            return this;
        }

        public Builder<E> horizon(int ticks) {
            Validate.check(ticks >= 0, "horizon must not be negative, got " + ticks);
            return horizon(() -> ticks);
        }

        /** The most hostiles weighed in one plan, in your selector's order, read as each plan begins. */
        public Builder<E> limit(IntSupplier count) {
            this.limit = Validate.notNull(count, "count");
            return this;
        }

        public Builder<E> limit(int count) {
            Validate.check(count >= 0, "limit must not be negative, got " + count);
            return limit(() -> count);
        }

        public Hostiles<E> build() {
            List<String> missing = new ArrayList<>();
            if (targets == null) {
                missing.add("targets");
            }
            if (selector == null) {
                missing.add("selector");
            }
            if (prediction == null && lookahead == null) {
                missing.add("where they will be (predicted or a lookahead)");
            }
            if (rule == null) {
                missing.add("rule");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("a Hostiles needs: " + String.join(", ", missing));
            }
            return new Hostiles<>(this);
        }
    }
}
