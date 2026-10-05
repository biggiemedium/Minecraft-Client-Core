package dev.px.core.network.packet;

/**
 * The field names Core's own services read out of a {@link PacketDescription}.
 *
 * <p>Each is a role a describer gives a packet, whatever its kind. Movement
 * slots &mdash; position, velocity, rotation, ground &mdash; are typed fields on
 * the description. These are plain {@link PacketDescription#with fields}, so
 * they round-trip through a timeline recording like any other.
 *
 * <p>An adapter never writes these names by hand: {@link PacketDescription}'s
 * {@code withWorldAge}, {@code withTransaction}, {@code withLatency},
 * {@code withBrand}, {@code withChannels} and {@code asCorrection} fill them in.
 */
public final class PacketFields {

    /** Long. The world's total age in ticks, on the server's clock packet. Read by TPS. */
    public static final String WORLD_AGE = "worldAge";

    /** Long. The number of a packet the server sends for the client to answer. Read by anticheat detection. */
    public static final String TRANSACTION = "transaction";

    /** Long. The server's measured round trip to this client, in milliseconds. Read by lag. */
    public static final String LATENCY = "latency";

    /** String. The brand the server announced. Read by the server service. */
    public static final String BRAND = "brand";

    /** String. Channel names a server registered, joined by {@link #CHANNEL_SEPARATOR}. Read by the server service. */
    public static final String CHANNELS = "channels";

    /** Boolean. The packet overrules the client's own motion. Read by the timeline. */
    public static final String CORRECTION = "correction";

    /** Separates names inside {@link #CHANNELS}. */
    public static final String CHANNEL_SEPARATOR = ",";

    private PacketFields() {
    }
}
