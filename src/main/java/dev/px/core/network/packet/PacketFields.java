package dev.px.core.network.packet;

/**
 * The field names Core's own services read out of a {@link PacketDescription}.
 *
 * <p>Movement slots &mdash; position, velocity, rotation, ground &mdash; are typed
 * fields on the description. Everything the network services need is a plain
 * {@link PacketDescription#with field}, under one of these names, so that it
 * round-trips through a timeline recording like any other field.
 *
 * <p>An adapter rarely writes these by hand: the factories on
 * {@link PacketDescription} ({@code timeUpdate}, {@code transaction},
 * {@code keepAlive}, {@code brand}, {@code channels}) and
 * {@link PacketDescription#withLatency} fill them in.
 */
public final class PacketFields {

    /** Long. The id of a transaction, ping or keep-alive. */
    public static final String ID = "id";

    /** Long. The world's total age in ticks, on a {@link PacketKind#TIME_UPDATE}. */
    public static final String WORLD_AGE = "worldAge";

    /** String. The channel of a {@link PacketKind#PAYLOAD}, such as {@code minecraft:brand}. */
    public static final String CHANNEL = "channel";

    /** String. The server brand, on a brand payload. */
    public static final String BRAND = "brand";

    /** String. Channel names a server registered, joined by {@link #CHANNEL_SEPARATOR}. */
    public static final String CHANNELS = "channels";

    /** Long. The server's measured round trip to this client, in milliseconds. */
    public static final String LATENCY = "latency";

    /** Separates names inside {@link #CHANNELS}. A comma never appears in a channel identifier. */
    public static final String CHANNEL_SEPARATOR = ",";

    /** The brand channel from 1.13 on. */
    public static final String BRAND_CHANNEL = "minecraft:brand";

    /** The brand channel before 1.13. */
    public static final String LEGACY_BRAND_CHANNEL = "MC|Brand";

    /** The channel-registration channel from 1.13 on. */
    public static final String REGISTER_CHANNEL = "minecraft:register";

    /** The channel-registration channel before 1.13. */
    public static final String LEGACY_REGISTER_CHANNEL = "REGISTER";

    private PacketFields() {
    }
}
