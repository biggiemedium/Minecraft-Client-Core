package dev.px.projectile;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * Whoever throws or shoots, at the moment they do: where their eyes are, how
 * they are moving, and whether they are on the ground.
 *
 * <pre>{@code
 * Shooter you = Shooter.of(Game.eyePosition(), Game.velocity(), Game.isOnGround());
 * Launch launch = pearl.launch(you, Game.rotation());
 * }</pre>
 *
 * <p>The velocity is your game's own for the player, not a difference between
 * two positions: the projectile takes it as the game hands it over.
 *
 * <p>Immutable.
 */
@Getter
public final class Shooter {

    private final Vec3 eye;
    /** In blocks per tick. */
    private final Vec3 velocity;
    private final boolean onGround;

    private Shooter(Vec3 eye, Vec3 velocity, boolean onGround) {
        this.eye = eye;
        this.velocity = velocity;
        this.onGround = onGround;
    }

    public static Shooter of(Vec3 eye, Vec3 velocity, boolean onGround) {
        return new Shooter(Validate.notNull(eye, "eye"), Validate.notNull(velocity, "velocity"), onGround);
    }

    /** @return someone standing still on the ground with their eyes at {@code eye} */
    public static Shooter still(Vec3 eye) {
        return of(eye, Vec3.ZERO, true);
    }

    @Override
    public String toString() {
        return "Shooter(eye " + eye + ", velocity " + velocity + (onGround ? ", on the ground)" : ", in the air)");
    }
}
