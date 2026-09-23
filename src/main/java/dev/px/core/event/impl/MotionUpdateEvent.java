package dev.px.core.event.impl;

import dev.px.core.event.Stage;
import dev.px.core.event.StagedEvent;
import dev.px.core.math.Vec2;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * The player's own movement state, around the point the client reports it.
 *
 * <p>Posted {@link Stage#PRE} just before the client builds its movement packet
 * and {@link Stage#POST} just after, which in 1.8 terms is either side of
 * {@code onUpdateWalkingPlayer}. Between the two sit the movement packets the
 * client actually sent, so a recording shows what the client believed next to
 * what it told the server.
 *
 * <pre>{@code
 * Core.bus().post(new MotionUpdateEvent(Stage.PRE,
 *         MotionState.of(Vec3.of(p.posX, p.posY, p.posZ),
 *                        Vec3.of(p.motionX, p.motionY, p.motionZ), p.onGround),
 *         MovementInput.of(p.rotationYaw, p.movementInput.moveForward, p.movementInput.moveStrafe)
 *                 .withJump(p.movementInput.jump).withSneak(p.movementInput.sneak),
 *         p.isSprinting(), p.isSneaking(),
 *         Vec2.rotation(p.rotationYaw, p.rotationPitch),
 *         Vec2.rotation(p.prevRotationYaw, p.prevRotationPitch)));
 * }</pre>
 *
 * <p>An observation, not a hook: every field is final. The rotation here is
 * what the adapter reports, which is not necessarily what
 * {@link dev.px.core.movement.rotation.RotationService} holds &mdash; nothing
 * about this event requires that service to be in use.
 *
 * <p>Sprinting and sneaking are the player's <em>state</em>, separate from the
 * keys in {@link MovementInput}: a player can hold sprint and not be sprinting.
 */
@Getter
public final class MotionUpdateEvent extends StagedEvent {

    private final MotionState motion;

    /** The keys held this tick, or null if the adapter does not report them. */
    private final MovementInput input;

    private final boolean sprinting;
    private final boolean sneaking;
    private final Vec2 rotation;

    /** Last tick's rotation, or null if the adapter does not report it. */
    private final Vec2 previousRotation;

    public MotionUpdateEvent(Stage stage, MotionState motion, MovementInput input,
                             boolean sprinting, boolean sneaking,
                             Vec2 rotation, Vec2 previousRotation) {
        super(stage);
        this.motion = Validate.notNull(motion, "motion");
        this.input = input;
        this.sprinting = sprinting;
        this.sneaking = sneaking;
        this.rotation = Validate.notNull(rotation, "rotation");
        this.previousRotation = previousRotation;
    }
}
