package dev.px.combat.explosion.rule;

import dev.px.core.util.Validate;

/**
 * How hard an explosion hits at a distance, before armour: your game's formula.
 *
 * <p>Core ships none, because this is exactly the part that changes between
 * versions. Write yours from your version's mechanics:
 *
 * <pre>{@code
 * Falloff mine = Falloff.of(
 *         power -> 2 * power,                                   // how far it reaches
 *         (distance, exposure, power) -> {                      // what it does there
 *             double impact = (1 - distance / (2 * power)) * exposure;
 *             return 7 * power * (impact * impact + impact) + 1;
 *         });
 * }</pre>
 */
public interface Falloff {

    /** @return how far from the explosion anything is hurt at all */
    double range(double power);

    /**
     * @param distance from the explosion to the point your model measures to,
     *        never more than {@link #range}
     * @param exposure 0 to 1, from {@link Exposure}
     * @return damage before anything reduces it
     */
    double damage(double distance, double exposure, double power);

    static Falloff of(Range range, Curve curve) {
        Validate.notNull(range, "range");
        Validate.notNull(curve, "curve");
        return new Falloff() {
            @Override
            public double range(double power) {
                return range.of(power);
            }

            @Override
            public double damage(double distance, double exposure, double power) {
                return curve.damage(distance, exposure, power);
            }
        };
    }

    @FunctionalInterface
    interface Range {
        double of(double power);
    }

    @FunctionalInterface
    interface Curve {
        double damage(double distance, double exposure, double power);
    }
}
