package dev.px.core.network.packet;

/**
 * What a packet means, as the adapter's {@link PacketDescriber} classifies it.
 *
 * <p>Core cannot tell a teleport from a chat message; the describer can, and this
 * is the vocabulary it answers in. The version-specific knowledge lives in one
 * adapter class, and everything downstream &mdash; the timeline's corrections
 * and queries, TPS, lag, server and anticheat detection &mdash; works off these
 * constants.
 *
 * <p>Only classify what you care about. A describer that knows three packets
 * returns {@link #OTHER} for the rest, and every service reading this skips what
 * it does not recognise.
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
     * Transactions, pings and teleport confirms: packets that exist to be
     * answered, and so the ones that tie an outbound action to its reply.
     *
     * <p>Inbound, this is also what an anticheat uses to measure the client's
     * latency: a transaction (1.8&ndash;1.16) or ping (1.17+) every tick, which a
     * vanilla server never sends. Give each one its number with
     * {@link PacketDescription#transaction} so the pattern can be read.
     */
    TRANSACTION,

    /**
     * Keep-alives. Separate from {@link #TRANSACTION} because vanilla sends them
     * on its own schedule, so counting them as transactions would make every
     * server look like it runs an anticheat.
     */
    KEEP_ALIVE,

    /**
     * The server's world clock: {@code S03PacketTimeUpdate} on 1.8.9,
     * {@code WorldTimeUpdateS2CPacket} on modern versions. Vanilla sends one every
     * twenty ticks, which is what TPS is estimated from.
     */
    TIME_UPDATE,

    /**
     * A plugin message on a named channel: the server brand, channel
     * registrations, and whatever the server's plugins say to their client mods.
     */
    PAYLOAD,

    /** Anything else. */
    OTHER;

    /** @return whether an applied packet of this kind overrules the client's motion. */
    public boolean isCorrection() {
        return this == TELEPORT || this == VELOCITY || this == EXPLOSION;
    }
}
