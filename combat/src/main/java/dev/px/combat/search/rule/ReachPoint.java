package dev.px.combat.search.rule;

/**
 * Which point of a block or crystal a reach distance is measured to, from the
 * local player's eyes.
 */
public enum ReachPoint {

    /** The centre of the base block, or of the crystal's box. */
    CENTRE,

    /** The nearest point of the base block, or of the crystal's box: the most lenient. */
    NEAREST
}
