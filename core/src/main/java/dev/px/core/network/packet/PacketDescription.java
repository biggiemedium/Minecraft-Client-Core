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
 * <p>A name is required, and a {@link PacketKind} of yours unless the packet is
 * {@link PacketKind#OTHER}; everything else is optional and null when absent.
 * The typed slots &mdash; position, velocity, rotation, ground &mdash; are the
 * ones movement analysis reads, so they are fields rather than map entries.
 * Anything else a packet carries goes in {@link #with}.
 *
 * <h2>Roles</h2>
 *
 * <p>Core's services do not look at a packet's kind; they look for a role, which
 * the describer gives whichever packet plays it in its version:
 *
 * <pre>{@code
 * PacketDescription.of("S03PacketTimeUpdate", Packets.TIME).withWorldAge(p.getTotalWorldTime());     // TPS
 * PacketDescription.of("S32PacketConfirmTransaction", Packets.TRANSACTION).withTransaction(p.getActionNumber()); // anticheat
 * PacketDescription.of("S38PacketPlayerListItem", Packets.TAB).withLatency(self.getPing());          // lag
 * PacketDescription.of("S3FPacketCustomPayload", Packets.PAYLOAD).withBrand(brand);                  // server
 * PacketDescription.of("S08PacketPlayerPosLook", Packets.TELEPORT).asCorrection();                  // timeline
 * }</pre>
 *
 * <p>A version that has no such packet simply never gives the role, and a
 * version where two packets play it gives it to both.
 *
 * <p>The {@link #withCorrelationKey correlation key} is what ties an outbound
 * packet to the inbound one that answers it: give a transaction, keep-alive or
 * teleport-confirm the same key in both directions (say {@code "teleport:42"})
 * and {@link dev.px.core.movement.timeline.Timeline#correlated} pairs them exactly, rather than by guessing
 * from timing.
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

    /** @param type the packet's name; a string literal, since obfuscated classes are called {@code a} */
    public static PacketDescription of(String type, PacketKind kind) {
        Validate.notNull(type, "type");
        Validate.notNull(kind, "kind");
        return new PacketDescription(type, kind, null, null, null, null, null,
                Collections.<String, Object>emptyMap());
    }

    /** A packet filed under {@link PacketKind#OTHER}. */
    public static PacketDescription of(String type) {
        return of(type, PacketKind.OTHER);
    }

    // ---------------------------------------------------------------- roles

    /**
     * The server's clock: TPS is the rate this advances against wall time.
     *
     * @param worldAge the world's <em>total</em> age in ticks, not the time of day,
     *        which can stand still
     */
    public PacketDescription withWorldAge(long worldAge) {
        return with(PacketFields.WORLD_AGE, worldAge);
    }

    /**
     * A packet the server sends for the client to answer, carrying its number:
     * what anticheat detection reads the server's timing and numbering from.
     *
     * <p>Also correlated as {@code "transaction:<id>"}, so give the client's
     * answer the same, with {@code withCorrelationKey}, and a timeline recording
     * pairs the two.
     *
     * <p>Give it only to packets the server sends to time the client. A packet
     * the server sends on a fixed schedule of its own &mdash; a keep-alive, on
     * most versions &mdash; is not one, and counting it would make every server
     * look like it runs an anticheat.
     */
    public PacketDescription withTransaction(long id) {
        return with(PacketFields.TRANSACTION, id).withCorrelationKey("transaction:" + id);
    }

    /** The server's measured round trip to this client: what {@code Core.lag()} reports as the ping. */
    public PacketDescription withLatency(int millis) {
        return with(PacketFields.LATENCY, millis);
    }

    /**
     * The brand the server announced about itself. Read it from a <em>copy</em> of
     * the packet's buffer if it has one: the game reads the same buffer after the
     * describer returns.
     */
    public PacketDescription withBrand(String brand) {
        Validate.notNull(brand, "brand");
        return with(PacketFields.BRAND, brand);
    }

    /** Channels the server registered. */
    public PacketDescription withChannels(Collection<String> channels) {
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
        return with(PacketFields.CHANNELS, joined.toString());
    }

    /**
     * The packet overrules the client's own motion: it sets the position, the
     * velocity, or pushes the player. A timeline recording follows each one that
     * is applied with a correction entry pointing back at the client's last
     * report.
     */
    public PacketDescription asCorrection() {
        return with(PacketFields.CORRECTION, true);
    }

    /** @return whether the describer said this packet overrules the client's motion */
    public boolean isCorrection() {
        return Boolean.TRUE.equals(fields.get(PacketFields.CORRECTION));
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
        return "PacketDescription(" + type + ", " + kind.getName() + ")";
    }
}
