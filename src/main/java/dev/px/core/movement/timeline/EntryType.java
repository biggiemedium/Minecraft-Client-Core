package dev.px.core.movement.timeline;

import dev.px.core.network.packet.PacketKind;

/** What a {@link TimelineEntry} records. */
public enum EntryType {

    /** A {@code TickEvent} PRE. Starts a new tick number. */
    TICK_START,

    /** A {@code TickEvent} POST. */
    TICK_END,

    /** A {@code PacketEvent}, in any phase. */
    PACKET,

    /** A {@code MotionUpdateEvent} PRE: what the client believed before reporting. */
    MOTION_PRE,

    /** A {@code MotionUpdateEvent} POST. */
    MOTION_POST,

    /**
     * The server overruling the client: an applied packet whose
     * {@link PacketKind#isCorrection() kind is a correction}. Written straight
     * after that packet's entry and linked to it.
     */
    CORRECTION,

    /** A {@code WorldEvent}: a world loaded or unloaded. */
    WORLD,

    /** A named point dropped by {@link TimelineRecorder#mark}. */
    MARK
}
