package dev.px.projectile.aim;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.projectile.Launch;
import dev.px.projectile.flight.Trajectory;
import lombok.Getter;

/**
 * A way to hit something: the rotation to launch along, and what then happens.
 *
 * <pre>{@code
 * Aim shot = solver.at(you, target);
 * if (shot != null) {
 *     Core.rotations().request(this, shot.getRotation());   // how you turn is yours
 *     drawPath(shot.getTrajectory(), shot.getImpact());           // the path, up to where it meets them
 * }
 * }</pre>
 *
 * <p>The rotation is a direction only. Turning to it, how smoothly, and when to
 * let go are your client's.
 *
 * <p>Immutable.
 */
@Getter
public final class Aim {

    /** Where to look when the projectile leaves: yaw and pitch. */
    private final Vec2 rotation;
    private final Arc arc;
    /** The point it was aimed through: on the target where it will be, or the point asked for. */
    private final Vec3 aimPoint;
    /**
     * The target where it is expected when the projectile arrives; {@code null}
     * when a point was aimed at. A stand-in when the lookahead moved it.
     */
    private final Tracked<?> target;
    /** Where the projectile meets the target's box, or passes through the point asked for. */
    private final Vec3 impact;
    /** Ticks from now until it gets there, counting the delay before it leaves. */
    private final int arrivalTick;
    /** The projectile as it would leave. */
    private final Launch launch;
    /**
     * Its flight through the world, clear until the impact. The flight does not
     * look for the target, which is somewhere else by then: draw it to the
     * {@link #getImpact() impact}.
     */
    private final Trajectory trajectory;

    Aim(Vec2 rotation, Arc arc, Vec3 aimPoint, Tracked<?> target, Vec3 impact, int arrivalTick, Launch launch,
        Trajectory trajectory) {
        this.rotation = rotation;
        this.arc = arc;
        this.aimPoint = aimPoint;
        this.target = target;
        this.impact = impact;
        this.arrivalTick = arrivalTick;
        this.launch = launch;
        this.trajectory = trajectory;
    }

    @Override
    public String toString() {
        return String.format("Aim(%s arc, yaw %.3f pitch %.3f, arrives in %d ticks)",
                arc, rotation.getYaw(), rotation.getPitch(), arrivalTick);
    }
}
