package dev.px.core.control;

import dev.px.core.movement.simulation.MovementInput;

/**
 * Writes the movement keys.
 *
 * <p>One method, and the only part of movement arbitration that needs the game.
 * {@link ControlService} decides <em>whose</em> keys are held; this decides how
 * they reach the game's input on your version.
 *
 * <pre>{@code
 * public final class PlayerMovementSink implements MovementSink {
 *
 *     public void apply(MovementInput input) {
 *         net.minecraft.util.MovementInput keys = mc.thePlayer.movementInput;   // the game's own, same name
 *         float applied = mc.thePlayer.rotationYaw;              // the yaw the game moves with this tick
 *         MovementCorrection.Input fixed = MovementCorrection.correct(
 *                 input.getYaw(), input.getForward(), input.getStrafe(), applied);
 *         keys.moveForward = fixed.getForward();
 *         keys.moveStrafe = fixed.getStrafe();
 *         keys.jump = input.isJump();
 *         keys.sneak = input.isSneak();
 *         mc.thePlayer.setSprinting(input.isSprint());
 *     }
 * }
 * }</pre>
 *
 * <p>The keys are meant for {@link MovementInput#getYaw()}: whoever claimed them
 * worked out that walking forward at that yaw goes where they want. If the yaw
 * your game moves with this tick is a different one &mdash; another module won
 * the rotation, or rotations are written after the player moves &mdash;
 * {@link dev.px.core.movement.MovementCorrection} turns the keys into the ones
 * that go the same way at the yaw it has, as above. When the two agree it
 * changes nothing.
 *
 * <p>Pass the keys before anything scales them: sneaking and the per-tick input
 * scale are the game's to apply, as they are when the player presses the keys.
 *
 * <p>Called on the game thread, only while a claim is winning, and must not
 * block. When no claim wins, nothing is called and the player's own keys stand.
 */
@FunctionalInterface
public interface MovementSink {

    void apply(MovementInput input);
}
