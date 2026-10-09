package dev.px.core.flow;

/**
 * The player's controls a {@link Step} can claim, and declares with
 * {@link Step#uses}.
 *
 * <p>Core's own controls, not game concepts: they are what
 * {@link dev.px.core.movement.rotation.RotationService} and
 * {@link dev.px.core.control.ControlService} arbitrate. Declaring them is what
 * lets a reflex pause only the flows that need the same ones, before they fight
 * over them.
 */
public enum Control {

    /** Where the player looks. */
    ROTATION,

    /** The movement keys. */
    MOVEMENT,

    /** The attack button. */
    ATTACK,

    /** The use button. */
    USE
}
