package dev.px.core.movement.timeline;

import dev.px.core.event.impl.PacketEvent.Phase;
import dev.px.core.network.packet.PacketKind;
import dev.px.core.util.Validate;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A finished recording: every entry in seq order, and the questions worth asking
 * of them.
 *
 * <p>The queries are about relationships rather than single entries, because
 * that is what a timeline is for:
 *
 * <pre>{@code
 * Timeline timeline = Core.timeline().end();
 *
 * // what arrived in answer to a movement packet, within five ticks
 * for (TimelineEntry sent : timeline.ofKind(PacketKind.MOVEMENT)) {
 *     List<TimelineEntry> replies = timeline.responsesTo(sent, PacketKind.TELEPORT, 5);
 * }
 *
 * // the exact reply to a transaction, by key rather than by timing
 * List<TimelineEntry> answer = timeline.correlated(transaction);
 *
 * // how long a teleport sat in the network queue before it took effect
 * TimelineEntry received = timeline.linked(appliedTeleport);
 * long queuedNanos = appliedTeleport.getNanos() - received.getNanos();
 *
 * // everything between two marks
 * List<TimelineEntry> attempt = timeline.between("jump", "landed");
 * }</pre>
 *
 * <p>Immutable, and safe to hand to another thread. Lookups by seq are binary
 * searches; the rest are linear scans, which is fine for analysis and not meant
 * to be run every tick.
 */
public final class Timeline {

    @Getter
    private final String label;

    /** Entries in seq order. */
    @Getter
    private final List<TimelineEntry> entries;

    /** How many ticks began during the recording. */
    @Getter
    private final long ticks;

    /** Entries evicted because the recording outgrew its capacity; the oldest go first. */
    @Getter
    private final long dropped;

    /** Monotonic nanoseconds from the start of the recording to its end. */
    @Getter
    private final long durationNanos;

    /** Whatever the client attached with {@link TimelineRecorder#putMetadata}. */
    @Getter
    private final Map<String, String> metadata;

    Timeline(String label, List<TimelineEntry> entries, long ticks, long dropped,
             long durationNanos, Map<String, String> metadata) {
        this.label = label;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.ticks = ticks;
        this.dropped = dropped;
        this.durationNanos = durationNanos;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // -------------------------------------------------------------- lookup

    /** @return the entry with this seq, or null if it was dropped or never existed */
    public TimelineEntry find(long seq) {
        int low = 0;
        int high = entries.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long at = entries.get(mid).getSeq();
            if (at < seq) {
                low = mid + 1;
            } else if (at > seq) {
                high = mid - 1;
            } else {
                return entries.get(mid);
            }
        }
        return null;
    }

    /** @return entries with {@code fromSeq <= seq <= toSeq} */
    public List<TimelineEntry> between(long fromSeq, long toSeq) {
        List<TimelineEntry> found = new ArrayList<>();
        for (TimelineEntry entry : entries) {
            if (entry.getSeq() > toSeq) {
                break;
            }
            if (entry.getSeq() >= fromSeq) {
                found.add(entry);
            }
        }
        return found;
    }

    /** @return the first {@link EntryType#MARK} with this label, or null */
    public TimelineEntry mark(String label) {
        for (TimelineEntry entry : entries) {
            if (entry.getType() == EntryType.MARK && entry.getLabel().equals(label)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * @return the entries strictly between the first mark called {@code from} and
     *         the first mark called {@code to} after it
     * @throws IllegalArgumentException if either mark is missing
     */
    public List<TimelineEntry> between(String from, String to) {
        TimelineEntry start = mark(from);
        Validate.check(start != null, "no mark called \"" + from + "\"");
        TimelineEntry end = null;
        for (TimelineEntry entry : entries) {
            if (entry.getSeq() > start.getSeq() && entry.getType() == EntryType.MARK
                    && entry.getLabel().equals(to)) {
                end = entry;
                break;
            }
        }
        Validate.check(end != null, "no mark called \"" + to + "\" after \"" + from + "\"");
        return between(start.getSeq() + 1, end.getSeq() - 1);
    }

    public List<TimelineEntry> inTick(long tick) {
        return filter(entry -> entry.getTick() == tick);
    }

    public List<TimelineEntry> ofType(EntryType type) {
        return filter(entry -> entry.getType() == type);
    }

    /** @return packet entries of this kind, in every phase */
    public List<TimelineEntry> ofKind(PacketKind kind) {
        return filter(entry -> entry.isPacket() && entry.getKind() == kind);
    }

    public List<TimelineEntry> filter(Predicate<? super TimelineEntry> test) {
        List<TimelineEntry> found = new ArrayList<>();
        for (TimelineEntry entry : entries) {
            if (test.test(entry)) {
                found.add(entry);
            }
        }
        return found;
    }

    // ------------------------------------------------------- relationships

    /**
     * @return the other half of a link, either way round, or null
     *
     * <p>Given an APPLIED packet, its RECEIVED entry; given a RECEIVED packet, the
     * APPLIED entry pointing back at it; given a CORRECTION, the packet that
     * caused it.
     */
    public TimelineEntry linked(TimelineEntry entry) {
        Validate.notNull(entry, "entry");
        if (entry.hasLink()) {
            return find(entry.getLinkedSeq());
        }
        for (TimelineEntry later : entries) {
            if (later.getSeq() > entry.getSeq() && later.getLinkedSeq() == entry.getSeq()
                    && later.getType() == entry.getType()) {
                return later;
            }
        }
        return null;
    }

    /**
     * @param kind the kind of reply to look for, or null for any
     * @param withinTicks how many ticks after the action's own tick to look
     * @return inbound packets that arrived after {@code action}, in arrival order
     *
     * <p>Arrival means the RECEIVED entry, which is the true order off the wire.
     * An APPLIED entry counts only if it has no RECEIVED half, for adapters that
     * post just one phase. This is correlation by time; where both sides carry a
     * key, {@link #correlated} is exact.
     */
    public List<TimelineEntry> responsesTo(TimelineEntry action, PacketKind kind, int withinTicks) {
        Validate.notNull(action, "action");
        Validate.check(withinTicks >= 0, "withinTicks must not be negative, got " + withinTicks);
        long lastTick = action.getTick() + withinTicks;
        List<TimelineEntry> found = new ArrayList<>();
        for (TimelineEntry entry : entries) {
            if (entry.getSeq() <= action.getSeq()) {
                continue;
            }
            if (entry.getTick() > lastTick) {
                break;
            }
            boolean arrival = entry.getPhase() == Phase.RECEIVED
                    || (entry.getPhase() == Phase.APPLIED && !entry.hasLink());
            if (entry.isPacket() && arrival && (kind == null || entry.getKind() == kind)) {
                found.add(entry);
            }
        }
        return found;
    }

    /**
     * @return packets travelling the other way with the same correlation key, in
     *         seq order, or an empty list if {@code entry} has no key
     */
    public List<TimelineEntry> correlated(TimelineEntry entry) {
        Validate.notNull(entry, "entry");
        String key = entry.getCorrelationKey();
        if (key == null) {
            return Collections.emptyList();
        }
        return filter(other -> other.isPacket()
                && other.getDirection() != entry.getDirection()
                && key.equals(other.getCorrelationKey()));
    }

    @Override
    public String toString() {
        return "Timeline(" + label + ", " + entries.size() + " entries, " + ticks + " ticks"
                + (dropped > 0 ? ", " + dropped + " dropped" : "") + ")";
    }
}
