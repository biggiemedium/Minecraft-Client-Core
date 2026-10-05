package dev.px.combat.vector.replay;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.monitor.DamageSample;
import dev.px.combat.vector.TestVector;

import java.util.Locale;

/**
 * One vector replayed: what the profile predicts now, against what really
 * happened, and whether that is close enough.
 *
 * <p>Immutable.
 */
public final class VectorOutcome {

    private final TestVector vector;
    private final DamageEstimate predicted;
    private final boolean passed;

    VectorOutcome(TestVector vector, DamageEstimate predicted, boolean passed) {
        this.vector = vector;
        this.predicted = predicted;
        this.passed = passed;
    }

    public TestVector getVector() {
        return vector;
    }

    /** @return what the profile predicts for this vector now */
    public DamageEstimate getPredicted() {
        return predicted;
    }

    /** @return observed minus predicted: positive when the profile under-predicts */
    public double getError() {
        return vector.getObserved() - predicted.getDamage();
    }

    public boolean isPassed() {
        return passed;
    }

    /** @return which rule this vector tests, by how exposed and how mitigated it is now */
    public DamageSample.Bucket getBucket() {
        return DamageSample.Bucket.of(predicted);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %s: predicted %.3f, observed %s%.3f (%s)",
                passed ? "pass" : "FAIL", getBucket(), predicted.getDamage(),
                vector.isPopped() ? ">=" : "", vector.getObserved(), vector.getExplosive().getName());
    }
}
