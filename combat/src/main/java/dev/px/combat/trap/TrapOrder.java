package dev.px.combat.trap;

/** Which of a trap's cells to place first. */
public enum TrapOrder {

    /**
     * The likeliest way out first. Walled in at the feet, as in a hole, the only
     * way out is up: the roof first, then the head ring. In the open, the feet ring
     * first, the side they are likeliest to walk out of first, then the roof, which
     * stops them jumping onto the ring, then the head ring.
     */
    ESCAPES_FIRST,

    /** Feet ring, head ring, roof, extras: bottom up, each layer standing on the last. */
    BOTTOM_UP
}
