package dev.px.combat.search.option;

/** Why an option was worth taking. */
public enum Trigger {

    /** It does at least the minimum damage. */
    MINIMUM,

    /** It does less, but the target is low enough, or faceplacing is forced, for the faceplace minimum. */
    FACEPLACE,

    /** It does less, but the target's armour is worn enough for the armour-break minimum. */
    ARMOUR_BREAK,

    /** It kills, as the lethal multiplier judges it. */
    LETHAL
}
