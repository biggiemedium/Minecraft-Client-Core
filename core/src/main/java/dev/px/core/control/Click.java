package dev.px.core.control;

/**
 * The two buttons a player acts on the world with.
 *
 * <p>Controls, not game concepts: which key or mouse button each is bound to,
 * and what pressing it does to whatever is under the crosshair, is the game's
 * and your adapter's. Core only arbitrates who gets to press them.
 */
public enum Click {

    /** The attack button: hitting an entity, breaking a block. */
    ATTACK,

    /** The use button: placing, eating, drawing a bow, opening a door. */
    USE
}
