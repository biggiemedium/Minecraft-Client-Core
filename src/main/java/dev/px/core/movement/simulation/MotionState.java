package dev.px.core.movement.simulation;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.math.MovementMath;
import dev.px.core.util.math.PhysicsProfile;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Everything a tick of movement needs to know about where something is.
 *
 * <p>Position, velocity and whether it is standing on something. Three fields,
 * because that is genuinely all the game's own movement step reads &mdash;
 * anything else it uses comes from the input or from the world.
 *
 * <p>Immutable, so a predicted future state and the state it came from can both
 * be held at once. That is the whole point: {@link Simulation#trace} hands back a
 * list of these and none of them can invalidate another.
 *
 * <p>Position is the feet, matching the game: the hitbox rises from it and is
 * centred on it horizontally.
 */
@Getter
@EqualsAndHashCode
public final class MotionState {

    private final Vec3 position;
    private final Vec3 velocity;
    private final boolean onGround;

    private MotionState(Vec3 position, Vec3 velocity, boolean onGround) {
        this.position = position;
        this.velocity = velocity;
        this.onGround = onGround;
    }

    public static MotionState of(Vec3 position, Vec3 velocity, boolean onGround) {
        Validate.notNull(position, "position");
        Validate.notNull(velocity, "velocity");
        return new MotionState(position, velocity, onGround);
    }

    /** Standing still on the ground. The usual starting point for a prediction. */
    public static MotionState at(Vec3 position) {
        return of(position, Vec3.ZERO, true);
    }

    public MotionState withPosition(Vec3 position) {
        return of(position, velocity, onGround);
    }

    public MotionState withVelocity(Vec3 velocity) {
        return of(position, velocity, onGround);
    }

    public MotionState withOnGround(boolean onGround) {
        return of(position, velocity, onGround);
    }

    /** @return the hitbox at this position, using the profile's dimensions. */
    public Box hitbox(PhysicsProfile profile) {
        Validate.notNull(profile, "profile");
        return Box.around(position, profile.getHitboxWidth(), profile.getHitboxHeight());
    }

    public Box hitbox(double width, double height) {
        return Box.around(position, width, height);
    }

    /** @return horizontal speed in blocks per tick, which is what a speed readout means. */
    public double getHorizontalSpeed() {
        return MovementMath.speed(velocity);
    }

    @Override
    public String toString() {
        return "MotionState(" + position + ", v=" + velocity + ", onGround=" + onGround + ")";
    }
}
