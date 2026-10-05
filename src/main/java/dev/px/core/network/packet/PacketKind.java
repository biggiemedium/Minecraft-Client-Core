package dev.px.core.network.packet;

/**
 * What a packet is, in your client's own words.
 *
 * <p>Core has no list of packets and never will: a game that adds a packet,
 * removes one, or splits one in two must not need a change here. You declare
 * the kinds you care about, usually as an enum, the same way module categories
 * work, and your {@link PacketDescriber} files each packet under one:
 *
 * <pre>{@code
 * public enum Packets implements PacketKind {
 *     MOVEMENT, TELEPORT, VELOCITY, TRANSACTION, KEEP_ALIVE, TIME, PAYLOAD
 * }
 * }</pre>
 *
 * <p>A kind is a label for you: for filtering a {@link dev.px.core.network.PacketListener},
 * querying a timeline, reading a recording. <b>None of Core's services read
 * it.</b> What they read are roles the describer gives a description, whatever
 * its kind &mdash; {@link PacketDescription#withWorldAge} for TPS,
 * {@link PacketDescription#withTransaction} for anticheat detection,
 * {@link PacketDescription#asCorrection} for the timeline, and so on. So a
 * version with no teleport packet, or three of them, is described in full
 * without Core knowing.
 *
 * <p>Kinds are compared by {@linkplain #getName() name}, which is also what a
 * timeline recording stores, so two kinds with the same name are the same kind.
 */
public interface PacketKind {

    /** What a packet is filed under when the describer gave it no kind. */
    PacketKind OTHER = Other.INSTANCE;

    /** @return the name recordings store and comparisons use; an enum's constant name by default */
    default String getName() {
        return toString();
    }

    /** @return whether this kind has the same name as {@code other} */
    default boolean is(PacketKind other) {
        return other != null && getName().equals(other.getName());
    }
}

/** The one kind Core defines, so a packet nobody described still has one. */
enum Other implements PacketKind {
    INSTANCE;

    @Override
    public String getName() {
        return "OTHER";
    }
}
