package dev.px.core.movement.simulation;

import dev.px.core.math.MathUtil;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * What the player is doing this tick: which way they face and which keys are down.
 *
 * <p>Yaw belongs here rather than on {@link MotionState} because it is an input
 * to movement, not a property of motion &mdash; the game's own step takes the
 * keys and the facing together and produces a direction from the pair. Bundling
 * them also keeps {@link Simulation#step} to three arguments.
 *
 * <p>Sign conventions match the game's fields so an adapter can pass them
 * straight through: forward is positive walking forward, and <b>strafe is
 * positive walking left</b>.
 *
 * <pre>{@code
 * MovementInput held = MovementInput.of(player.rotationYaw, forwardKey, strafeKey)
 *         .withSprint(player.isSprinting())
 *         .withJump(jumpKey);
 * }</pre>
 *
 * <p>Immutable, and cheap enough to rebuild every tick.
 */
@Getter
@EqualsAndHashCode
public final class MovementInput {

    private final float yaw;
    private final double forward;
    private final double strafe;
    private final boolean jump;
    private final boolean sneak;
    private final boolean sprint;

    private MovementInput(float yaw, double forward, double strafe,
                          boolean jump, boolean sneak, boolean sprint) {
        this.yaw = MathUtil.wrapDegrees(yaw);
        this.forward = forward;
        this.strafe = strafe;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
    }

    /** No keys held, facing this way. What a falling or coasting player is doing. */
    public static MovementInput none(float yaw) {
        return new MovementInput(yaw, 0d, 0d, false, false, false);
    }

    public static MovementInput of(float yaw, double forward, double strafe) {
        return new MovementInput(yaw, forward, strafe, false, false, false);
    }

    /** Walking straight ahead. */
    public static MovementInput forward(float yaw) {
        return of(yaw, 1d, 0d);
    }

    public MovementInput withYaw(float yaw) {
        return new MovementInput(yaw, forward, strafe, jump, sneak, sprint);
    }

    public MovementInput withKeys(double forward, double strafe) {
        return new MovementInput(yaw, forward, strafe, jump, sneak, sprint);
    }

    public MovementInput withJump(boolean jump) {
        return new MovementInput(yaw, forward, strafe, jump, sneak, sprint);
    }

    public MovementInput withSneak(boolean sneak) {
        return new MovementInput(yaw, forward, strafe, jump, sneak, sprint);
    }

    public MovementInput withSprint(boolean sprint) {
        return new MovementInput(yaw, forward, strafe, jump, sneak, sprint);
    }

    /** @return whether any directional key is held. */
    public boolean isMoving() {
        return forward != 0d || strafe != 0d;
    }

    @Override
    public String toString() {
        return "MovementInput(yaw=" + yaw + ", f=" + forward + ", s=" + strafe
                + (jump ? ", jump" : "") + (sneak ? ", sneak" : "") + (sprint ? ", sprint" : "") + ")";
    }
}
