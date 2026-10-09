package dev.px.combat.fight;

import dev.px.core.movement.simulation.MovementInput;

/**
 * The keys in a {@link Fight}: strafing, keeping a distance, jumping at the
 * right moment.
 *
 * <pre>{@code
 * // your strafe: circle the target, meant for the yaw that faces it
 * Footwork<EntityPlayer> circle = b -> {
 *     float yaw = b.eyes().rotationTo(b.target().getCenter()).getYaw();
 *     return MovementInput.of(yaw, 0, clockwise ? 1 : -1).withSprint(true);
 * };
 * }</pre>
 *
 * <p>A {@link MovementInput} carries the yaw its keys are meant for, so the
 * keys need not match where the head looks: your
 * {@link dev.px.core.control.MovementSink} corrects them to the yaw the player
 * faces, which is what lets the head stay on the target while the feet circle
 * it. Null leaves the keys this tick to anyone else &mdash; a travel step running
 * beside the fight, or the player.
 *
 * @param <E> the game's type for what is fought
 */
@FunctionalInterface
public interface Footwork<E> extends Part<E> {

    /** @return the keys to hold this tick; null to leave them alone */
    MovementInput keys(Bout<? extends E> b);
}
