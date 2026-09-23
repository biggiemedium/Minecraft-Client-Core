package dev.px.core.movement.timeline;

import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.MotionUpdateEvent;
import dev.px.core.event.impl.PacketEvent;
import dev.px.core.event.impl.PacketEvent.Direction;
import dev.px.core.event.impl.PacketEvent.Phase;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.core.util.collect.CircularQueue;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Records what the client and server said to each other, and what the client
 * was doing at the time, in one exactly-ordered timeline.
 *
 * <pre>{@code
 * Core.timeline().setDescriber(new MyPacketDescriber());   // once, from the adapter
 *
 * Core.timeline().begin("velocity test");
 * // ... play ...
 * Core.timeline().mark("hit");
 * // ... play ...
 * Timeline timeline = Core.timeline().end();
 * TimelineJson.write(timeline, Files.newBufferedWriter(path));
 * }</pre>
 *
 * <h2>What it listens to</h2>
 *
 * <p>Only events Core already has, or that an adapter posts for its own modules
 * anyway: {@link TickEvent} for tick boundaries, {@link PacketEvent} for traffic,
 * {@link MotionUpdateEvent} for the player's state, {@link WorldEvent} for world
 * changes. It reads nothing from {@code RotationService} or
 * {@code SimulationService}, and neither knows it exists; either can be unused
 * without this noticing, and the other way round.
 *
 * <p><b>Idle costs nothing.</b> Until {@link #begin} it holds no subscription and
 * no buffer. {@link #end} gives both back.
 *
 * <h2>Ordering</h2>
 *
 * <p>Handlers run on whichever thread posted, so packets arrive here from the
 * network thread while ticks arrive from the game thread. Every entry's seq,
 * timestamp and tick are assigned under one lock, in the same critical section
 * that appends it, which makes seq order the one true order: two entries from
 * different threads are never tied, never swapped, and their timestamps never
 * run backwards. Describing a packet happens outside the lock, so a slow
 * describer delays only its own thread.
 *
 * <p>Tick boundaries listen at the extremes of the priority range &mdash; the
 * start before any other PRE handler, the end after every POST one &mdash; so a
 * packet a module sends from its tick handler lands inside the tick it was sent
 * from. Everything else listens last, with cancelled events included, so an
 * entry records the event as it finally was and says if it was cancelled.
 *
 * <h2>Links</h2>
 *
 * <p>An inbound packet posted RECEIVED and then APPLIED with the same instance
 * gets two entries, the second pointing at the first. An applied packet whose
 * kind {@link PacketKind#isCorrection() is a correction} is followed immediately
 * by a {@link EntryType#CORRECTION} entry pointing at it, with the seq of the
 * client's last reported motion in its {@code clientSeq} field, so the state
 * being overruled is one lookup away.
 */
public final class TimelineRecorder implements Service {

    /** About seven minutes at thirty entries a tick. */
    public static final int DEFAULT_CAPACITY = 262_144;

    /** Received packets still waiting for their APPLIED half. Beyond this, the oldest are forgotten. */
    private static final int PENDING_LIMIT = 4096;

    private final CoreLogger logger;
    private final EventBus bus;
    private final LongSupplier clock;
    private final Listener listener = new Listener();
    private final Object lock = new Object();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    @Getter
    private volatile PacketDescriber describer = PacketDescriber.DEFAULT;

    /** Which packets to keep, or null for all. Tick, motion and world entries are always kept. */
    @Getter
    private volatile Predicate<? super PacketDescription> filter;

    @Getter
    private int capacity = DEFAULT_CAPACITY;

    private volatile boolean warnedAboutDescriber;

    // Everything below is guarded by lock.
    private boolean recording;
    private String label;
    private CircularQueue<TimelineEntry> buffer;
    private Map<IdentityKey, Long> pending;
    private long nextSeq;
    private long tick;
    private long ticks;
    private long dropped;
    private long originNanos;
    private long lastNanos;
    private long lastMotionSeq;

    public TimelineRecorder(CoreLogger logger, EventBus bus) {
        this(logger, bus, System::nanoTime);
    }

    /** @param nanoClock monotonic nanoseconds; injectable so a test can run it by hand */
    public TimelineRecorder(CoreLogger logger, EventBus bus, LongSupplier nanoClock) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        this.clock = Validate.notNull(nanoClock, "nanoClock");
    }

    @Override
    public String getName() {
        return "Timeline";
    }

    /** Nothing to do: recording starts at {@link #begin}, not with the client. */
    @Override
    public void start() {
    }

    @Override
    public void stop() {
        if (isRecording()) {
            Timeline discarded = end();
            logger.debug("Discarded an unfinished timeline recording: " + discarded);
        }
    }

    // ------------------------------------------------------------- settings

    /** @param describer how to read the adapter's packets, or null for {@link PacketDescriber#DEFAULT} */
    public void setDescriber(PacketDescriber describer) {
        this.describer = describer != null ? describer : PacketDescriber.DEFAULT;
        this.warnedAboutDescriber = false;
    }

    /** @param filter which packets to keep, or null for all */
    public void setFilter(Predicate<? super PacketDescription> filter) {
        this.filter = filter;
    }

    /**
     * @param capacity the most entries a recording keeps; past it, the oldest are
     *        dropped and counted in {@code Timeline.getDropped()}
     */
    public void setCapacity(int capacity) {
        Validate.check(capacity > 0, "capacity must be positive, got " + capacity);
        synchronized (lock) {
            requireState(!recording, "cannot change capacity while recording");
            this.capacity = capacity;
        }
    }

    /** Attached to every timeline this records, and written into its JSON header. */
    public void putMetadata(String key, String value) {
        Validate.notNull(key, "key");
        synchronized (lock) {
            if (value == null) {
                metadata.remove(key);
            } else {
                metadata.put(key, value);
            }
        }
    }

    // ------------------------------------------------------------ recording

    public boolean isRecording() {
        synchronized (lock) {
            return recording;
        }
    }

    /**
     * Starts recording. Tick numbers, seq and timestamps all start from zero.
     *
     * @throws IllegalStateException if already recording
     */
    public void begin(String label) {
        Validate.notNull(label, "label");
        synchronized (lock) {
            requireState(!recording, "already recording \"" + this.label + "\"");
            this.label = label;
            this.buffer = CircularQueue.of(capacity);
            this.pending = new LinkedHashMap<IdentityKey, Long>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<IdentityKey, Long> eldest) {
                    return size() > PENDING_LIMIT;
                }
            };
            this.nextSeq = 0L;
            this.tick = 0L;
            this.ticks = 0L;
            this.dropped = 0L;
            this.originNanos = clock.getAsLong();
            this.lastNanos = 0L;
            this.lastMotionSeq = TimelineEntry.NO_LINK;
            this.recording = true;
        }
        // Outside the lock: the bus has locks of its own, and a handler holding
        // the bus's while waiting on ours is not a thing to make possible.
        bus.subscribe(listener);
    }

    /**
     * Stops recording and hands back everything recorded.
     *
     * @throws IllegalStateException if not recording
     */
    public Timeline end() {
        bus.unsubscribe(listener);
        synchronized (lock) {
            requireState(recording, "not recording");
            Timeline timeline = snapshotLocked();
            recording = false;
            buffer = null;
            pending = null;
            label = null;
            return timeline;
        }
    }

    /** @return everything recorded so far, without stopping */
    public Timeline snapshot() {
        synchronized (lock) {
            requireState(recording, "not recording");
            return snapshotLocked();
        }
    }

    /**
     * Drops a named point into the timeline, for {@link Timeline#between(String, String)}.
     *
     * @return the mark's entry, or null if not recording
     */
    public TimelineEntry mark(String label) {
        Validate.notNull(label, "label");
        TimelineEntry.Builder entry = TimelineEntry.builder(EntryType.MARK);
        entry.label = label;
        return append(entry);
    }

    // ------------------------------------------------------------ internals

    private Timeline snapshotLocked() {
        long duration = Math.max(clock.getAsLong() - originNanos, lastNanos);
        return new Timeline(label, buffer.toList(), ticks, dropped, duration, metadata);
    }

    private static void requireState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** Stamps and appends. The only place seq, nanos and tick are assigned. */
    private TimelineEntry append(TimelineEntry.Builder entry) {
        synchronized (lock) {
            if (!recording) {
                return null;
            }
            // Clamped as well as monotonic, so a clock that does step backwards
            // still cannot put a later seq at an earlier time.
            long now = Math.max(clock.getAsLong() - originNanos, lastNanos);
            lastNanos = now;
            TimelineEntry built = entry.build(nextSeq++, now, tick);
            if (buffer.add(built) != null) {
                dropped++;
            }
            return built;
        }
    }

    private void tickStarted() {
        synchronized (lock) {
            if (!recording) {
                return;
            }
            tick++;
            ticks++;
            append(TimelineEntry.builder(EntryType.TICK_START));
        }
    }

    private void packet(PacketEvent event) {
        PacketDescription description = describe(event.getPacket());
        Predicate<? super PacketDescription> keep = filter;
        if (keep != null && !keep.test(description)) {
            return;
        }

        TimelineEntry.Builder entry = TimelineEntry.builder(EntryType.PACKET).describe(description);
        entry.phase = event.getPhase();
        entry.cancelled = event.isCancelled();

        synchronized (lock) {
            if (!recording) {
                return;
            }
            IdentityKey key = new IdentityKey(event.getPacket());
            if (entry.phase == Phase.APPLIED) {
                Long received = pending.remove(key);
                if (received != null) {
                    entry.linkedSeq = received;
                }
            }
            TimelineEntry recorded = append(entry);

            if (entry.phase == Phase.RECEIVED && !entry.cancelled) {
                pending.put(key, recorded.getSeq());
            }
            if (entry.phase == Phase.APPLIED && !entry.cancelled && description.getKind().isCorrection()) {
                TimelineEntry.Builder correction = TimelineEntry.builder(EntryType.CORRECTION).describe(description);
                correction.direction = Direction.INBOUND;
                correction.linkedSeq = recorded.getSeq();
                Map<String, Object> fields = new LinkedHashMap<>(description.getFields());
                fields.put("clientSeq", lastMotionSeq);
                correction.fields = fields;
                append(correction);
            }
        }
    }

    private void motion(MotionUpdateEvent event) {
        TimelineEntry.Builder entry = TimelineEntry.builder(
                event.getStage() == Stage.PRE ? EntryType.MOTION_PRE : EntryType.MOTION_POST);
        entry.position = event.getMotion().getPosition();
        entry.velocity = event.getMotion().getVelocity();
        entry.onGround = event.getMotion().isOnGround();
        entry.input = event.getInput();
        entry.sprinting = event.isSprinting();
        entry.sneaking = event.isSneaking();
        entry.rotation = event.getRotation();
        entry.previousRotation = event.getPreviousRotation();
        entry.cancelled = event.isCancelled();
        synchronized (lock) {
            TimelineEntry recorded = append(entry);
            if (recorded != null) {
                lastMotionSeq = recorded.getSeq();
            }
        }
    }

    private void world(WorldEvent event) {
        TimelineEntry.Builder entry = TimelineEntry.builder(EntryType.WORLD);
        entry.label = event.getServerAddress();
        entry.fields = Collections.<String, Object>singletonMap("loaded", event.isLoaded());
        append(entry);
    }

    private PacketDescription describe(Object packet) {
        PacketDescriber current = describer;
        try {
            PacketDescription description = current.describe(packet);
            if (description != null) {
                return description;
            }
        } catch (Throwable thrown) {
            if (!warnedAboutDescriber) {
                warnedAboutDescriber = true;
                logger.error("PacketDescriber threw on " + packet.getClass().getName()
                        + "; recording it undescribed (further failures are not logged)", thrown);
            }
        }
        return PacketDescriber.DEFAULT.describe(packet);
    }

    /**
     * Subscribed only while recording.
     *
     * <p>A separate object rather than annotated methods on the service, so the
     * service can be registered and started without subscribing anything.
     */
    private final class Listener {

        @Subscribe(stage = Stage.PRE, priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onTickStart(TickEvent event) {
            tickStarted();
        }

        @Subscribe(stage = Stage.POST, priority = Integer.MIN_VALUE, receiveCancelled = true)
        private void onTickEnd(TickEvent event) {
            append(TimelineEntry.builder(EntryType.TICK_END));
        }

        @Subscribe(priority = Integer.MIN_VALUE, receiveCancelled = true)
        private void onPacket(PacketEvent event) {
            packet(event);
        }

        @Subscribe(priority = Integer.MIN_VALUE, receiveCancelled = true)
        private void onMotion(MotionUpdateEvent event) {
            motion(event);
        }

        @Subscribe(priority = Integer.MIN_VALUE)
        private void onWorld(WorldEvent event) {
            world(event);
        }
    }

    /** Links RECEIVED to APPLIED by instance, whatever the packet's own equals says. */
    private static final class IdentityKey {

        private final Object target;

        private IdentityKey(Object target) {
            this.target = target;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey && ((IdentityKey) other).target == target;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(target);
        }
    }
}
