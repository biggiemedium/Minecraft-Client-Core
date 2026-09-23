package dev.px.core.movement.timeline;

/**
 * What a packet means for movement, as the adapter's {@link PacketDescriber}
 * classifies it.
 *
 * <p>Core cannot tell a teleport from a chat message; the describer can, and this
 * is the vocabulary it answers in. The version-specific knowledge lives in one
 * adapter class, and everything downstream &mdash; corrections, queries, filters
 * &mdash; works off these constants.
 */
public enum PacketKind {

    /** The client reporting its own position, rotation or ground state. */
    MOVEMENT,

    /** The client reporting an action: sprint or sneak toggles, swings, use, attack. */
    ACTION,

    /** The server setting the player's position outright. */
    TELEPORT,

    /** The server setting the player's velocity. */
    VELOCITY,

    /** An explosion, which pushes the player. */
    EXPLOSION,

    /** Abilities: flying, fly speed, walk speed. */
    ABILITIES,

    /** A potion effect added or removed. */
    EFFECT,

    /** An entity attribute, such as movement speed, changed. */
    ATTRIBUTE,

    /** A block or chunk change that may alter what the player collides with. */
    WORLD_STATE,

    /** Respawn or dimension change. */
    RESPAWN,

    /**
     * Keep-alives, transactions, pings and teleport confirms: packets that exist
     * to be answered, and so the ones that tie an outbound action to its reply.
     */
    TRANSACTION,

    /** Anything else. */
    OTHER;

    /** @return whether an applied packet of this kind overrules the client's motion. */
    public boolean isCorrection() {
        return this == TELEPORT || this == VELOCITY || this == EXPLOSION;
    }
}
