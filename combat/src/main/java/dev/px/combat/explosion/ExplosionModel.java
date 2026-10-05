package dev.px.combat.explosion;

import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.explosion.rule.Falloff;
import dev.px.combat.explosion.rule.Mitigation;
import dev.px.combat.world.BlockView;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

/**
 * How much an explosion hurts a target, put together from four small rules
 * that each answer one question:
 *
 * <table summary="">
 *   <tr><td>{@link #builder() measureFrom}</td><td>which point of the target distance is measured to</td></tr>
 *   <tr><td>{@link Exposure}</td><td>how much of the target the blast reaches</td></tr>
 *   <tr><td>{@link Falloff}</td><td>how hard it hits at that distance and exposure</td></tr>
 *   <tr><td>{@link Mitigation}</td><td>what reduces it before it lands</td></tr>
 * </table>
 *
 * <pre>{@code
 * ExplosionModel<EntityLivingBase> model = ExplosionModel.<EntityLivingBase>builder()
 *         .measureFrom(Tracked::getPosition)          // the wiki gives the feet, for current Java
 *         .exposure(Exposure.sampled(myGrid))
 *         .falloff(myFalloff)
 *         .mitigation(Mitigation.chain(myDifficulty, myArmour, myBlastProtection))
 *         .build();
 * }</pre>
 *
 * <p>Every rule is yours and every one is required: Core assumes nothing about
 * any version. When a version changes how explosions work, it changes one rule,
 * and everything built on this model &mdash; crystals, anchors, beds &mdash; is
 * correct again.
 *
 * <p>Immutable and safe to share.
 *
 * @param <E> the game's type for what is being hurt
 */
public final class ExplosionModel<E> {

    private final MeasurePoint measure;
    private final Exposure exposure;
    private final Falloff falloff;
    private final Mitigation<? super E> mitigation;

    private ExplosionModel(Builder<E> builder) {
        this.measure = builder.measure;
        this.exposure = builder.exposure;
        this.falloff = builder.falloff;
        this.mitigation = builder.mitigation;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /**
     * @return what {@code explosive} going off at {@code origin} does to
     *         {@code target}, step by step. Exposure is only worked out within
     *         range, so a far target costs no raycasting
     */
    public DamageEstimate estimate(Vec3 origin, Explosive explosive, Tracked<? extends E> target, BlockView blocks) {
        Validate.notNull(origin, "origin");
        Validate.notNull(explosive, "explosive");
        Validate.notNull(target, "target");
        Validate.notNull(blocks, "blocks");
        double power = explosive.getPower();
        double distance = measure.of(target).distanceTo(origin);
        if (distance > falloff.range(power)) {
            return DamageEstimate.outOfRange(distance);
        }
        double exposed = exposure.of(origin, target.getBox(), blocks);
        double raw = falloff.damage(distance, exposed, power);
        double landed = Math.max(0d, mitigation.apply(raw, target));
        return new DamageEstimate(true, distance, exposed, raw, landed);
    }

    /** @return the damage {@link #estimate} predicts, after mitigation */
    public double damage(Vec3 origin, Explosive explosive, Tracked<? extends E> target, BlockView blocks) {
        return estimate(origin, explosive, target, blocks).getDamage();
    }

    public Falloff getFalloff() {
        return falloff;
    }

    /**
     * @return this model with a different exposure rule. With {@link Exposure#FULL}
     *         it predicts the most an explosion could do if nothing were in the
     *         way, at no raycasting cost: an upper bound, for any model whose
     *         damage does not fall as exposure rises
     */
    public ExplosionModel<E> withExposure(Exposure exposure) {
        Builder<E> copy = new Builder<>();
        copy.measure = measure;
        copy.exposure = Validate.notNull(exposure, "exposure");
        copy.falloff = falloff;
        copy.mitigation = mitigation;
        return new ExplosionModel<>(copy);
    }

    /** Which point of a target an explosion's distance is measured to. */
    @FunctionalInterface
    public interface MeasurePoint {
        Vec3 of(Tracked<?> target);
    }

    public static final class Builder<E> {

        private MeasurePoint measure;
        private Exposure exposure;
        private Falloff falloff;
        private Mitigation<? super E> mitigation;

        private Builder() {
        }

        /** Required. Which point of the target distance is measured to, such as {@code Tracked::getPosition}. */
        public Builder<E> measureFrom(MeasurePoint measure) {
            this.measure = Validate.notNull(measure, "measure");
            return this;
        }

        /** Required. */
        public Builder<E> exposure(Exposure exposure) {
            this.exposure = Validate.notNull(exposure, "exposure");
            return this;
        }

        /** Required. */
        public Builder<E> falloff(Falloff falloff) {
            this.falloff = Validate.notNull(falloff, "falloff");
            return this;
        }

        /** Required; {@link Mitigation#none()} to say there is none. */
        public Builder<E> mitigation(Mitigation<? super E> mitigation) {
            this.mitigation = Validate.notNull(mitigation, "mitigation");
            return this;
        }

        /** @throws IllegalStateException naming each rule not given */
        public ExplosionModel<E> build() {
            StringBuilder missing = new StringBuilder();
            if (measure == null) {
                missing.append(" measureFrom");
            }
            if (exposure == null) {
                missing.append(" exposure");
            }
            if (falloff == null) {
                missing.append(" falloff");
            }
            if (mitigation == null) {
                missing.append(" mitigation");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("an ExplosionModel needs every rule; missing:" + missing);
            }
            return new ExplosionModel<>(this);
        }
    }
}
