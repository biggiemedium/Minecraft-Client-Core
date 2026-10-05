package dev.px.combat.vector.replay;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.state.TargetState;
import dev.px.combat.vector.TestVector;
import dev.px.combat.vector.VectorSet;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replays recorded explosions against a version profile, with no game: does
 * the profile predict what really happened?
 *
 * <pre>{@code
 * // the profile under test: your rules, with mitigation written against TargetState
 * ExplosionModel<TargetState> profile = ExplosionModel.<TargetState>builder()
 *         .measureFrom(Tracked::getPosition)
 *         .exposure(myExposure)
 *         .falloff(myFalloff)
 *         .mitigation(Mitigation.ofState(myDifficulty, myArmour, myProtection))
 *         .build();
 *
 * ReplayResult result = VectorReplay.against(profile).run(VectorJson.read(reader));
 * assert result.isPassed() : result;
 * }</pre>
 *
 * <p>Each vector's target is put back where it stood, at its size, with its
 * recorded {@link TargetState} as its object, among the blocks it was recorded
 * among. A vector passes when the prediction is within {@link #tolerance} of
 * what was observed; a popped one, whose observed damage is only a lower bound,
 * passes when the prediction is at least that.
 *
 * <p>Write your live mitigation with {@code Mitigation.fromState} and the same
 * steps, and the profile you replay is the profile you play with.
 */
public final class VectorReplay {

    /** In damage: health is a float in most games, so an exact profile still differs in the last places. */
    public static final double DEFAULT_TOLERANCE = 0.05d;

    private final ExplosionModel<TargetState> profile;
    private double tolerance = DEFAULT_TOLERANCE;

    private VectorReplay(ExplosionModel<TargetState> profile) {
        this.profile = profile;
    }

    public static VectorReplay against(ExplosionModel<TargetState> profile) {
        return new VectorReplay(Validate.notNull(profile, "profile"));
    }

    /** @param damage how far a prediction may be from what was observed and still pass */
    public VectorReplay tolerance(double damage) {
        Validate.check(damage >= 0d, "tolerance must not be negative");
        this.tolerance = damage;
        return this;
    }

    public ReplayResult run(VectorSet set) {
        Validate.notNull(set, "set");
        return run(set.getVectors());
    }

    public ReplayResult run(List<TestVector> vectors) {
        Validate.notNull(vectors, "vectors");
        Stage stage = new Stage();
        List<VectorOutcome> outcomes = new ArrayList<>(vectors.size());
        for (TestVector vector : vectors) {
            Tracked<TargetState> target = stage.place(vector);
            DamageEstimate predicted = profile.estimate(vector.getOrigin(), vector.getExplosive(), target,
                    vector.getBlocks());
            outcomes.add(new VectorOutcome(vector, predicted, passes(vector, predicted)));
        }
        return new ReplayResult(outcomes);
    }

    private boolean passes(TestVector vector, DamageEstimate predicted) {
        if (vector.isPopped()) {
            return predicted.getDamage() >= vector.getObserved() - tolerance;
        }
        return Math.abs(vector.getObserved() - predicted.getDamage()) <= tolerance;
    }

    /**
     * Puts one vector's target back in a world of its own, through Core's entity
     * service, so the profile sees an ordinary tracked entity.
     */
    private static final class Stage implements EntitySource<TargetState> {

        private final EntityService entities;
        private final EntityTracker<TargetState> tracker;
        private TestVector current;

        Stage() {
            entities = new EntityService(SILENT, new CoreEventBus(SILENT));
            tracker = entities.register(EntityTracker.of(TargetState.class));
            entities.setSource(this);
        }

        Tracked<TargetState> place(TestVector vector) {
            current = vector;
            entities.refresh();
            return tracker.get(vector.getState());
        }

        @Override
        public Iterable<TargetState> entities() {
            return current == null ? Collections.<TargetState>emptyList()
                    : Collections.singletonList(current.getState());
        }

        @Override
        public TargetState self() {
            return null;
        }

        @Override
        public double x(TargetState state) {
            return current.getPosition().getX();
        }

        @Override
        public double y(TargetState state) {
            return current.getPosition().getY();
        }

        @Override
        public double z(TargetState state) {
            return current.getPosition().getZ();
        }

        @Override
        public double width(TargetState state) {
            return current.getWidth();
        }

        @Override
        public double height(TargetState state) {
            return current.getHeight();
        }

        @Override
        public double eyeHeight(TargetState state) {
            return current.getEyeHeight();
        }
    }

    private static final CoreLogger SILENT = new CoreLogger() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable thrown) {
        }

        @Override
        public void debug(String message) {
        }
    };
}
