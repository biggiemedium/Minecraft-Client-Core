package dev.px.core.network.packet;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link PacketDescriber} says a packet is.
 *
 * <p>A name and a {@link PacketKind} are required; everything else is optional
 * and null when absent. The typed slots &mdash; position, velocity, rotation,
 * ground &mdash; are the ones movement analysis reads, so they are fields rather
 * than map entries. Anything else a packet carries goes in {@link #with}.
 *
 * <p>The {@link #withCorrelationKey correlation key} is what ties an outbound
 * packet to the inbound one that answers it: give a transaction, keep-alive or
 * teleport-confirm the same key in both directions (say {@code "teleport:42"})
 * and {@link dev.px.core.movement.timeline.Timeline#correlated} pairs them exactly, rather than by guessing
 * from timing.
 *
 * <h2>Network packets</h2>
 *
 * <p>The TPS, lag, server and anticheat services read a handful of packets, and
 * each has a factory that fills in the kind and the fields they look for:
 *
 * <pre>{@code
 * PacketDescription.timeUpdate("S03PacketTimeUpdate", p.getTotalWorldTime());
 * PacketDescription.transaction("S32PacketConfirmTransaction", p.getActionNumber());
 * PacketDescription.keepAlive("S00PacketKeepAlive", p.func_149134_c());
 * PacketDescription.brand("S3FPacketCustomPayload", brand);
 * }</pre>
 *
 * <p>Immutable; each {@code with} returns a copy.
 */
@Getter
@EqualsAndHashCode
public final class PacketDescription {

    private final String type;
    private final PacketKind kind;
    private final Vec3 position;
    private final Vec3 velocity;
    private final Vec2 rotation;
    private final Boolean onGround;
    private final String correlationKey;

    /** Extra values, in insertion order. Strings, booleans, longs and doubles only. */
    private final Map<String, Object> fields;

    private PacketDescription(String type, PacketKind kind, Vec3 position, Vec3 velocity,
                              Vec2 rotation, Boolean onGround, String correlationKey,
                              Map<String, Object> fields) {
        this.type = type;
        this.kind = kind;
        this.position = position;
        this.velocity = velocity;
        this.rotation = rotation;
        this.onGround = onGround;
        this.correlationKey = correlationKey;
        this.fields = fields;
    }

    public static PacketDescription of(String type, PacketKind kind) {
        Validate.notNull(type, "type");
        Validate.notNull(kind, "kind");
        return new PacketDescription(type, kind, null, null, null, null, null,
                Collections.<String, Object>emptyMap());
    }

    // ------------------------------------------------------- network packets

    /**
     * The server's world clock, which TPS is estimated from.
     *
     * @param worldAge the world's <em>total</em> age in ticks, not the time of day:
     *        the time of day stands still under {@code doDaylightCycle false}, and
     *        the age never does
     */
    public static PacketDescription timeUpdate(String type, long worldAge) {
        return of(type, PacketKind.TIME_UPDATE).with(PacketFields.WORLD_AGE, worldAge);
    }

    /**
     * A transaction or ping, in either direction.
     *
     * <p>Correlated as {@code "transaction:<id>"}, so an inbound one and the
     * client's answer pair up in a timeline recording.
     */
    public static PacketDescription transaction(String type, long id) {
        return of(type, PacketKind.TRANSACTION)
                .withId(id)
                .withCorrelationKey("transaction:" + id);
    }

    /** A keep-alive, in either direction, correlated as {@code "keepalive:<id>"}. */
    public static PacketDescription keepAlive(String type, long id) {
        return of(type, PacketKind.KEEP_ALIVE)
                .withId(id)
                .withCorrelationKey("keepalive:" + id);
    }

    /** A plugin message on {@code channel}, with nothing Core needs to read from its body. */
    public static PacketDescription payload(String type, String channel) {
        Validate.notNull(channel, "channel");
        return of(type, PacketKind.PAYLOAD).with(PacketFields.CHANNEL, channel);
    }

    /**
     * The server brand: {@code "Paper"}, {@code "vanilla"},
     * {@code "BungeeCord (git:...) <- Spigot"}.
     *
     * <p>Read the brand from a <em>copy</em> of the payload buffer. The game reads
     * the same buffer after the describer returns, and a describer that consumed
     * it leaves the game an empty one.
     */
    public static PacketDescription brand(String type, String brand) {
        Validate.notNull(brand, "brand");
        return payload(type, PacketFields.BRAND_CHANNEL).with(PacketFields.BRAND, brand);
    }

    /** Channels the server registered, from a {@code minecraft:register} or {@code REGISTER} payload. */
    public static PacketDescription channels(String type, Collection<String> channels) {
        Validate.notNull(channels, "channels");
        StringBuilder joined = new StringBuilder();
        for (String channel : channels) {
            if (channel == null || channel.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(PacketFields.CHANNEL_SEPARATOR);
            }
            joined.append(channel);
        }
        return payload(type, PacketFields.REGISTER_CHANNEL).with(PacketFields.CHANNELS, joined.toString());
    }

    /** @param id the number a transaction, ping or keep-alive carries */
    public PacketDescription withId(long id) {
        return with(PacketFields.ID, id);
    }

    /**
     * The server's measured round trip to this client, from whichever packet
     * reports it &mdash; the player-list entry for the local player, on vanilla.
     */
    public PacketDescription withLatency(int millis) {
        return with(PacketFields.LATENCY, millis);
    }

    // ------------------------------------------------------------- reading

    /** @return the field as a long, or null when it is absent or not a number */
    public Long getLong(String key) {
        Object value = fields.get(key);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    /** @return the field as a string, or null when it is absent or not a string */
    public String getString(String key) {
        Object value = fields.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** @return whether this is a {@link PacketKind#PAYLOAD} on one of the given channels */
    public boolean isPayloadOn(String... channels) {
        if (kind != PacketKind.PAYLOAD) {
            return false;
        }
        String channel = getString(PacketFields.CHANNEL);
        for (String candidate : channels) {
            if (candidate.equalsIgnoreCase(channel)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- building

    public PacketDescription withPosition(Vec3 position) {
        return new PacketDescription(type, kind, position, velocity, rotation, onGround, correlationKey, fields);
    }

    public PacketDescription withVelocity(Vec3 velocity) {
        return new PacketDescription(type, kind, position, velocity, rotation, onGround, correlationKey, fields);
    }

    public PacketDescription withRotation(Vec2 rotation) {
        return new PacketDescription(type, kind, position, velocity, rotation, onGround, correlationKey, fields);
    }

    public PacketDescription withOnGround(boolean onGround) {
        return new PacketDescription(type, kind, position, velocity, rotation, onGround, correlationKey, fields);
    }

    public PacketDescription withCorrelationKey(String correlationKey) {
        return new PacketDescription(type, kind, position, velocity, rotation, onGround, correlationKey, fields);
    }

    /**
     * @param value a string, boolean or number; other objects are recorded as
     *        their {@code toString()}
     *
     * <p>Numbers are widened to {@code Long} or {@code Double} on the way in, so a
     * recording reads back from JSON equal to the one that was written.
     */
    public PacketDescription with(String key, Object value) {
        Validate.notNull(key, "key");
        Validate.notNull(value, "value");
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.put(key, normalise(value));
        return new PacketDescription(type, kind, position, velocity, rotation, onGround, correlationKey,
                Collections.unmodifiableMap(copy));
    }

    static Object normalise(Object value) {
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double || value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return "PacketDescription(" + type + ", " + kind + ")";
    }
}
