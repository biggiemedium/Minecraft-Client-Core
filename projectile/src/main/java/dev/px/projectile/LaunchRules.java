package dev.px.projectile;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.math.RotationMath;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.function.DoubleSupplier;

/**
 * How a projectile leaves whoever launches it: how hard, at what angle to where
 * they look, from where, and with how much of their own movement.
 *
 * <pre>{@code
 * // From the wiki's Projectile#Initial_conditions, for current Java
 * LaunchRules pearl = LaunchRules.builder()
 *         .power(1.5)
 *         .spread(0.0172275)
 *         .shooterVelocity(ShooterVelocity.ALL_BUT_VERTICAL_ON_GROUND)
 *         .origin(LaunchOrigin.below(0.1))                  // not on the wiki: your game's
 *         .build();
 *
 * LaunchRules potion = LaunchRules.builder()
 *         .power(0.5)
 *         .pitchOffset(-20f)                                // thrown 20 degrees above where you look
 *         ...
 *
 * LaunchRules bow = LaunchRules.builder()
 *         .power(() -> Game.bowPower(Game.useTicks()))       // 0 to 3 with the draw: your game's formula
 *         ...
 *
 * Launch launch = pearl.launch(you, rotation);
 * }</pre>
 *
 * <p>Following the <a href="https://minecraft.wiki/w/Projectile#Initial_conditions">wiki</a>:
 * the projectile's direction is where the shooter looks, turned down by the pitch
 * offset; it is scaled by the power, and then gets some of the shooter's
 * velocity. Its randomness, a nudge of up to {@link #getSpread() spread} on each
 * axis of the direction before it is scaled, is left out of the launch and kept
 * as a bound: see {@link Launch#getSpread()}. The wiki gives the powers for
 * thrown projectiles, crossbows and tridents, and says a bow's goes from 0 to 3
 * with how far it is drawn without saying how; that formula is your game's.
 *
 * <p>The power is read when {@link #launch} is called, so a bow's can follow the
 * draw. Immutable otherwise.
 */
@Getter
public final class LaunchRules {

    @Getter(AccessLevel.NONE)
    private final DoubleSupplier power;
    /** Added to the shooter's pitch, in degrees: positive aims lower, as pitch does. */
    private final float pitchOffset;
    /** How far the randomness can nudge each axis of the unit direction, before it is scaled by the power. */
    private final double spread;
    private final ShooterVelocity shooterVelocity;
    private final LaunchOrigin origin;

    private LaunchRules(Builder builder) {
        this.power = builder.power;
        this.pitchOffset = builder.pitchOffset;
        this.spread = builder.spread;
        this.shooterVelocity = builder.shooterVelocity;
        this.origin = builder.origin;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param shooter  whoever launches it, as they are now
     * @param rotation where they look
     * @return the projectile as it leaves them, with no randomness
     */
    public Launch launch(Shooter shooter, Vec2 rotation) {
        Validate.notNull(shooter, "shooter");
        Validate.notNull(rotation, "rotation");
        double strength = power.getAsDouble();
        Vec3 velocity = RotationMath.direction(rotation.getYaw(), rotation.getPitch() + pitchOffset).scale(strength);
        Vec3 own = shooter.getVelocity();
        switch (shooterVelocity) {
            case ALL:
                velocity = velocity.add(own);
                break;
            case ALL_BUT_VERTICAL_ON_GROUND:
                velocity = velocity.add(own.getX(), shooter.isOnGround() ? 0d : own.getY(), own.getZ());
                break;
            default:
                break;
        }
        return new Launch(origin.from(shooter.getEye(), rotation), velocity, rotation, strength, spread * strength);
    }

    /** @return the power, read now */
    public double currentPower() {
        return power.getAsDouble();
    }

    public static final class Builder {

        private DoubleSupplier power;
        private float pitchOffset;
        private double spread;
        private ShooterVelocity shooterVelocity;
        private LaunchOrigin origin;

        private Builder() {
        }

        /** Required: how fast it leaves, in blocks per tick, before the shooter's movement is added. */
        public Builder power(double power) {
            Validate.check(power >= 0d, "power can not be negative");
            this.power = () -> power;
            return this;
        }

        /** Required, or {@link #power(double)}: a power read at each launch, such as a bow's with its draw. */
        public Builder power(DoubleSupplier power) {
            this.power = Validate.notNull(power, "power");
            return this;
        }

        /** Added to where the shooter looks, in degrees; positive aims lower. None unless set. */
        public Builder pitchOffset(float degrees) {
            this.pitchOffset = degrees;
            return this;
        }

        /**
         * How far the randomness can nudge each axis of the unit direction, before
         * the power scales it. None unless set; it only bounds where it may go.
         */
        public Builder spread(double perAxis) {
            Validate.check(perAxis >= 0d, "spread can not be negative");
            this.spread = perAxis;
            return this;
        }

        /** Required: how much of the shooter's velocity it carries. */
        public Builder shooterVelocity(ShooterVelocity shooterVelocity) {
            this.shooterVelocity = Validate.notNull(shooterVelocity, "shooterVelocity");
            return this;
        }

        /** Required: where it starts, from the shooter's eyes. */
        public Builder origin(LaunchOrigin origin) {
            this.origin = Validate.notNull(origin, "origin");
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public LaunchRules build() {
            StringBuilder missing = new StringBuilder();
            if (power == null) {
                missing.append(" power");
            }
            if (shooterVelocity == null) {
                missing.append(" shooterVelocity");
            }
            if (origin == null) {
                missing.append(" origin");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("LaunchRules need:" + missing);
            }
            return new LaunchRules(this);
        }
    }
}
