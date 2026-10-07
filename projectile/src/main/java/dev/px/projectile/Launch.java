package dev.px.projectile;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import lombok.Getter;

/**
 * One projectile as it leaves: where it starts, how fast and which way, and how
 * far its randomness could push it.
 *
 * <pre>{@code
 * Launch launch = pearl.launch(you, rotation);
 * Trajectory path = flight.launch(launch);
 * }</pre>
 *
 * <p>The velocity is the one the game would give it with no randomness: the
 * middle of everywhere it could go. {@link #getSpread()} says how far from it
 * the randomness can reach.
 *
 * <p>Immutable.
 */
@Getter
public final class Launch {

    private final Vec3 position;
    /** In blocks per tick, with no randomness. */
    private final Vec3 velocity;
    /** The rotation it was launched along, before any pitch offset. */
    private final Vec2 rotation;
    /** The power its direction was scaled by. */
    private final double power;
    /** How far its randomness can move the velocity on each axis, in blocks per tick. */
    private final double spread;

    Launch(Vec3 position, Vec3 velocity, Vec2 rotation, double power, double spread) {
        this.position = position;
        this.velocity = velocity;
        this.rotation = rotation;
        this.power = power;
        this.spread = spread;
    }

    @Override
    public String toString() {
        return "Launch(" + position + " at " + velocity + ", spread " + spread + ")";
    }
}
