package dev.px.combat.explosion;

import java.util.Locale;

/**
 * What an {@link ExplosionModel} predicts one explosion does to one target, and
 * how it got there: the distance, the exposure, the damage before and after
 * mitigation.
 *
 * <p>The breakdown is what makes a wrong prediction diagnosable: damage too high
 * with the right exposure points at mitigation, not at the falloff.
 *
 * <p>Immutable.
 */
public final class DamageEstimate {

    private final boolean inRange;
    private final double distance;
    private final double exposure;
    private final double raw;
    private final double damage;

    DamageEstimate(boolean inRange, double distance, double exposure, double raw, double damage) {
        this.inRange = inRange;
        this.distance = distance;
        this.exposure = exposure;
        this.raw = raw;
        this.damage = damage;
    }

    /** An estimate from its parts: for reading one back from a recording. */
    public static DamageEstimate of(boolean inRange, double distance, double exposure, double raw, double damage) {
        return new DamageEstimate(inRange, distance, exposure, raw, damage);
    }

    static DamageEstimate outOfRange(double distance) {
        return new DamageEstimate(false, distance, 0d, 0d, 0d);
    }

    /** @return whether the target was close enough to be hurt at all */
    public boolean isInRange() {
        return inRange;
    }

    /** @return from the explosion to the point the model measures to */
    public double getDistance() {
        return distance;
    }

    /** @return 0 to 1; 0 when out of range, since it is not worked out */
    public double getExposure() {
        return exposure;
    }

    /** @return damage from the falloff, before any mitigation */
    public double getRaw() {
        return raw;
    }

    /** @return damage after every mitigation step; never negative */
    public double getDamage() {
        return damage;
    }

    @Override
    public String toString() {
        return inRange
                ? String.format(Locale.ROOT, "DamageEstimate(%.2f at %.2f blocks, exposure %.2f, raw %.2f)",
                        damage, distance, exposure, raw)
                : String.format(Locale.ROOT, "DamageEstimate(out of range at %.2f blocks)", distance);
    }
}
