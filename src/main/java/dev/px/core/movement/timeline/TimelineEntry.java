package dev.px.core.movement.timeline;

import dev.px.core.event.impl.PacketEvent.Direction;
import dev.px.core.event.impl.PacketEvent.Phase;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened, stamped with exactly when.
 *
 * <p>Three stamps, because each answers a different question:
 *
 * <ul>
 *   <li>{@code seq} &mdash; the global order. Unique, gapless within a
 *       recording, and assigned under the same lock that appends the entry, so
 *       two entries from different threads are ordered by seq and nothing else.
 *   <li>{@code nanos} &mdash; monotonic time since the recording
 *       began. Never decreases as seq increases, so it measures latency without
 *       being trusted for order.
 *   <li>{@code tick} &mdash; the client tick it happened during,
 *       counted from the start of the recording. A packet received on the network
 *       thread carries the tick that was running when it arrived.
 * </ul>
 *
 * <p>Everything else is present only where it applies and null otherwise: a
 * {@link EntryType#TICK_START} has no direction, a packet has no movement input.
 * Which fields each type fills in is listed on {@link EntryType}'s constants and
 * in the README.
 *
 * <p>Immutable.
 */
@Getter
@EqualsAndHashCode
public final class TimelineEntry {

    /** {@code linkedSeq} when there is no linked entry. */
    public static final long NO_LINK = -1L;

    private final long seq;
    private final long nanos;
    private final long tick;
    private final EntryType type;

    // --- packets and corrections
    private final Direction direction;
    private final Phase phase;
    private final String packetType;
    private final PacketKind kind;
    private final boolean cancelled;

    /**
     * The entry this one belongs with, or {@link #NO_LINK}.
     *
     * <p>An APPLIED packet points at its RECEIVED entry; a CORRECTION points at
     * the APPLIED packet that caused it.
     */
    private final long linkedSeq;

    private final String correlationKey;

    // --- movement and rotation
    private final Vec3 position;
    private final Vec3 velocity;
    private final Boolean onGround;
    private final MovementInput input;
    private final Boolean sprinting;
    private final Boolean sneaking;
    private final Vec2 rotation;
    private final Vec2 previousRotation;

    /** A mark's name, or a world's server address. */
    private final String label;

    private final Map<String, Object> fields;

    private TimelineEntry(Builder b) {
        this.seq = b.seq;
        this.nanos = b.nanos;
        this.tick = b.tick;
        this.type = b.type;
        this.direction = b.phase != null ? b.phase.getDirection() : b.direction;
        this.phase = b.phase;
        this.packetType = b.packetType;
        this.kind = b.kind;
        this.cancelled = b.cancelled;
        this.linkedSeq = b.linkedSeq;
        this.correlationKey = b.correlationKey;
        this.position = b.position;
        this.velocity = b.velocity;
        this.onGround = b.onGround;
        this.input = b.input;
        this.sprinting = b.sprinting;
        this.sneaking = b.sneaking;
        this.rotation = b.rotation;
        this.previousRotation = b.previousRotation;
        this.label = b.label;
        this.fields = b.fields.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
    }

    public boolean isInbound() {
        return direction == Direction.INBOUND;
    }

    public boolean isOutbound() {
        return direction == Direction.OUTBOUND;
    }

    public boolean isPacket() {
        return type == EntryType.PACKET;
    }

    public boolean hasLink() {
        return linkedSeq != NO_LINK;
    }

    /** @return position, velocity and ground as one state, or null unless all three are present */
    public MotionState getMotionState() {
        return position != null && velocity != null && onGround != null
                ? MotionState.of(position, velocity, onGround)
                : null;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder()
                .append('#').append(seq).append(" t").append(tick).append(' ').append(type);
        if (phase != null) {
            text.append(' ').append(phase);
        }
        if (packetType != null) {
            text.append(' ').append(packetType).append(" (").append(kind).append(')');
        }
        if (cancelled) {
            text.append(" cancelled");
        }
        if (label != null) {
            text.append(" \"").append(label).append('"');
        }
        if (hasLink()) {
            text.append(" -> #").append(linkedSeq);
        }
        return text.toString();
    }

    static Builder builder(EntryType type) {
        return new Builder(type);
    }

    /** Package-private: entries are made by the recorder and the JSON reader, nothing else. */
    static final class Builder {

        long seq;
        long nanos;
        long tick;
        final EntryType type;
        Direction direction;
        Phase phase;
        String packetType;
        PacketKind kind;
        boolean cancelled;
        long linkedSeq = NO_LINK;
        String correlationKey;
        Vec3 position;
        Vec3 velocity;
        Boolean onGround;
        MovementInput input;
        Boolean sprinting;
        Boolean sneaking;
        Vec2 rotation;
        Vec2 previousRotation;
        String label;
        Map<String, Object> fields = Collections.emptyMap();

        private Builder(EntryType type) {
            this.type = type;
        }

        Builder describe(PacketDescription description) {
            packetType = description.getType();
            kind = description.getKind();
            position = description.getPosition();
            velocity = description.getVelocity();
            rotation = description.getRotation();
            onGround = description.getOnGround();
            correlationKey = description.getCorrelationKey();
            fields = description.getFields();
            return this;
        }

        TimelineEntry build(long seq, long nanos, long tick) {
            this.seq = seq;
            this.nanos = nanos;
            this.tick = tick;
            return new TimelineEntry(this);
        }
    }
}
