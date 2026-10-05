package dev.px.combat.monitor;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.Explosive;
import dev.px.core.math.Vec3;

import java.util.Locale;

/**
 * One explosion, one target: what the model predicted, and what happened.
 *
 * <p>Immutable. Kept by {@link DamageMonitor#getSamples()} for logging or for
 * saving as test cases.
 */
public final class DamageSample {

    /** Which rule a sample tests, by how exposed and how armoured its target was. */
    public enum Bucket {
        /** Fully exposed and barely mitigated: the falloff alone decides it. */
        OPEN,
        /** Fully exposed but heavily mitigated: tests the mitigation. */
        ARMOURED,
        /** Partly covered: tests the exposure. */
        COVERED;

        /** @return which rule a prediction like {@code estimate} tests */
        public static Bucket of(DamageEstimate estimate) {
            if (estimate.getExposure() < FULLY_EXPOSED) {
                return COVERED;
            }
            double raw = estimate.getRaw();
            return raw > 0d && estimate.getDamage() / raw < LIGHTLY_MITIGATED ? ARMOURED : OPEN;
        }
    }

    /** Fully exposed, to within this. */
    static final double FULLY_EXPOSED = 0.999d;

    /** Mitigation leaving more than this share of the damage counts as barely any. */
    static final double LIGHTLY_MITIGATED = 0.9d;

    private final Explosive explosive;
    private final Vec3 origin;
    private final Vec3 target;
    private final boolean self;
    private final DamageEstimate predicted;
    private final double before;
    private final double observed;
    private final boolean popped;
    private final long tick;

    DamageSample(Explosive explosive, Vec3 origin, Vec3 target, boolean self, DamageEstimate predicted,
                 double before, double observed, boolean popped, long tick) {
        this.explosive = explosive;
        this.origin = origin;
        this.target = target;
        this.self = self;
        this.predicted = predicted;
        this.before = before;
        this.observed = observed;
        this.popped = popped;
        this.tick = tick;
    }

    public Explosive getExplosive() {
        return explosive;
    }

    public Vec3 getOrigin() {
        return origin;
    }

    /** @return where the target was when it went off */
    public Vec3 getTarget() {
        return target;
    }

    /** @return whether the target was the local player */
    public boolean isSelf() {
        return self;
    }

    /** @return the model's prediction, with its breakdown */
    public DamageEstimate getPredicted() {
        return predicted;
    }

    /** @return the target's {@link Vitals#pool} just before */
    public double getBefore() {
        return before;
    }

    /**
     * @return how much the pool fell. When the target {@link #isPopped() popped},
     *         only a lower bound: it took at least everything it had
     */
    public double getObserved() {
        return observed;
    }

    /** @return whether a totem, or death, ended it, so {@link #getObserved()} is only a lower bound */
    public boolean isPopped() {
        return popped;
    }

    /** @return observed minus predicted: positive when the model under-predicted */
    public double getError() {
        return observed - predicted.getDamage();
    }

    /** @return which rule this sample says most about */
    public Bucket getBucket() {
        return Bucket.of(predicted);
    }

    /** @return the monitor's tick count when it went off */
    public long getTick() {
        return tick;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "DamageSample(%s, predicted %.2f, observed %s%.2f, %s)",
                explosive.getName(), predicted.getDamage(), popped ? ">=" : "", observed, getBucket());
    }
}
