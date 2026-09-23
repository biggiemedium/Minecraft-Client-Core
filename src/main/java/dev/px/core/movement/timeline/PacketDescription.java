package dev.px.core.movement.timeline;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import lombok.EqualsAndHashCode;
import lombok.Getter;

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
 * and {@link Timeline#correlated} pairs them exactly, rather than by guessing
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

    public static PacketDescription of(String type, PacketKind kind) {
        Validate.notNull(type, "type");
        Validate.notNull(kind, "kind");
        return new PacketDescription(type, kind, null, null, null, null, null,
                Collections.<String, Object>emptyMap());
    }

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
