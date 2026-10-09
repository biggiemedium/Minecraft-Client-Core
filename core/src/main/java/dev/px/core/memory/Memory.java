package dev.px.core.memory;

import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.flow.Span;
import dev.px.core.service.Service;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * What every flow knows: typed facts, each with an age and, if you like, an
 * expiry.
 *
 * <pre>{@code
 * static final Fact<Boolean> UNMINABLE = Fact.of("unminable");
 * static final Fact<Vec3> STASH = Fact.of("stash");
 *
 * Core.memory().remember(UNMINABLE, pos, true, Span.seconds(300));   // skip it for five minutes
 * Core.memory().remember(STASH, chest);                              // for good
 *
 * if (Core.memory().knows(UNMINABLE, pos)) { ... }
 * Vec3 stash = Core.memory().recall(STASH);
 * for (Recollection<Boolean> skipped : Core.memory().all(UNMINABLE)) { ... }
 * }</pre>
 *
 * <p>A fact is either one value ({@code remember(fact, value)}) or one value per
 * subject &mdash; a block, an entity, a name &mdash; compared by
 * {@code equals}. Expiry is a {@link Span}: in ticks, or in wall-clock seconds.
 * An expired fact is gone: it is never recalled, and it is cleared out as time
 * passes.
 *
 * <p>Steps reach it through {@link dev.px.core.flow.FlowContext#remember} and
 * {@code recall}, which also show in the live view which facts each step
 * touched.
 *
 * <p>Kept for the session. The player leaving a world does not clear it, since
 * a fact may well hold for the next one; clear what belongs to one world
 * yourself, on {@link dev.px.core.event.impl.WorldEvent}.
 *
 * <p>Game thread only.
 */
public final class Memory implements Service {

    /** Ticks between clearing out expired facts. A tuning knob. */
    private static final int PURGE_EVERY = 20;

    /** Stands in for "no subject", so one map holds both kinds. */
    private static final Object NONE = new Object();

    private final EventBus bus;
    private final LongSupplier nanos;
    private final Map<Fact<?>, Map<Object, Entry>> facts = new LinkedHashMap<>();
    private Subscription tickSubscription;
    private long tick;

    public Memory(EventBus bus) {
        this(bus, System::nanoTime);
    }

    /** @param nanos the clock wall-clock expiry is measured against */
    public Memory(EventBus bus, LongSupplier nanos) {
        this.bus = Validate.notNull(bus, "bus");
        this.nanos = Validate.notNull(nanos, "nanos");
    }

    @Override
    public String getName() {
        return "Memory";
    }

    @Override
    public void start() {
        tickSubscription = bus.on(TickEvent.class, Priority.HIGHEST, event -> {
            if (event.getStage() == Stage.PRE) {
                tick();
            }
        });
    }

    @Override
    public void stop() {
        if (tickSubscription != null) {
            tickSubscription.close();
            tickSubscription = null;
        }
    }

    /**
     * Counts a tick, and every so often clears out what has expired. Called on
     * {@link TickEvent} once started; call it yourself if nothing posts ticks.
     */
    public void tick() {
        tick++;
        if (tick % PURGE_EVERY == 0) {
            purge();
        }
    }

    public long getTick() {
        return tick;
    }

    // ------------------------------------------------------------ remembering

    /** Remembers the one value of {@code fact}, for good. */
    public <T> void remember(Fact<T> fact, T value) {
        remember(fact, null, value, null);
    }

    /** Remembers the one value of {@code fact}, until {@code expiry} has passed; null for good. */
    public <T> void remember(Fact<T> fact, T value, Span expiry) {
        remember(fact, null, value, expiry);
    }

    /** Remembers {@code fact} about {@code subject}, for good. */
    public <T> void remember(Fact<T> fact, Object subject, T value) {
        remember(fact, subject, value, null);
    }

    /**
     * Remembers {@code fact} about {@code subject}, replacing what was known,
     * until {@code expiry} has passed.
     *
     * @param subject what it is about; null for a fact with one value
     * @param expiry  how long it holds; null for good
     */
    public <T> void remember(Fact<T> fact, Object subject, T value, Span expiry) {
        Validate.notNull(fact, "fact");
        Validate.notNull(value, "value");
        Map<Object, Entry> about = facts.get(fact);
        if (about == null) {
            about = new LinkedHashMap<>();
            facts.put(fact, about);
        }
        Object key = subject == null ? NONE : subject;
        about.remove(key);
        about.put(key, new Entry(value, tick, nanos.getAsLong(), expiry));
    }

    // ------------------------------------------------------------ recalling

    /** @return the one value of {@code fact}, or null if unknown or expired */
    public <T> T recall(Fact<T> fact) {
        return recall(fact, null);
    }

    /** @return {@code fact} about {@code subject}, or null if unknown or expired */
    @SuppressWarnings("unchecked")
    public <T> T recall(Fact<T> fact, Object subject) {
        Entry entry = live(fact, subject);
        return entry == null ? null : (T) entry.value;
    }

    /** @return whether {@code fact} about {@code subject} is known and has not expired */
    public boolean knows(Fact<?> fact, Object subject) {
        return live(fact, subject) != null;
    }

    public boolean knows(Fact<?> fact) {
        return knows(fact, null);
    }

    /** @return {@code fact} about {@code subject} with its age, or null */
    @SuppressWarnings("unchecked")
    public <T> Recollection<T> recollect(Fact<T> fact, Object subject) {
        Entry entry = live(fact, subject);
        return entry == null ? null : recollection(fact, subject, entry);
    }

    /** @return everything known of {@code fact}, oldest first */
    public <T> List<Recollection<T>> all(Fact<T> fact) {
        Validate.notNull(fact, "fact");
        Map<Object, Entry> about = facts.get(fact);
        if (about == null) {
            return Collections.emptyList();
        }
        long now = nanos.getAsLong();
        List<Recollection<T>> found = new ArrayList<>(about.size());
        for (Map.Entry<Object, Entry> e : about.entrySet()) {
            if (!e.getValue().expired(tick, now)) {
                found.add(recollection(fact, e.getKey() == NONE ? null : e.getKey(), e.getValue()));
            }
        }
        return found;
    }

    /** @return every fact with something known, for the live view */
    public List<Fact<?>> getFacts() {
        List<Fact<?>> known = new ArrayList<>();
        for (Map.Entry<Fact<?>, Map<Object, Entry>> e : facts.entrySet()) {
            if (!e.getValue().isEmpty()) {
                known.add(e.getKey());
            }
        }
        return known;
    }

    // ------------------------------------------------------------ forgetting

    /** @return whether there was something to forget */
    public boolean forget(Fact<?> fact, Object subject) {
        Map<Object, Entry> about = facts.get(Validate.notNull(fact, "fact"));
        return about != null && about.remove(subject == null ? NONE : subject) != null;
    }

    /** Forgets everything known of {@code fact}. @return how much was still known */
    public int forget(Fact<?> fact) {
        Map<Object, Entry> about = facts.remove(Validate.notNull(fact, "fact"));
        if (about == null) {
            return 0;
        }
        long now = nanos.getAsLong();
        int known = 0;
        for (Entry entry : about.values()) {
            if (!entry.expired(tick, now)) {
                known++;
            }
        }
        return known;
    }

    public void clear() {
        facts.clear();
    }

    /** @return how many things are known, not counting what has expired but not yet been cleared out */
    public int size() {
        int size = 0;
        for (Map<Object, Entry> about : facts.values()) {
            size += about.size();
        }
        return size;
    }

    /** Clears out everything that has expired. */
    public void purge() {
        long now = nanos.getAsLong();
        for (Iterator<Map<Object, Entry>> facts = this.facts.values().iterator(); facts.hasNext(); ) {
            Map<Object, Entry> about = facts.next();
            about.values().removeIf(entry -> entry.expired(tick, now));
            if (about.isEmpty()) {
                facts.remove();
            }
        }
    }

    // ------------------------------------------------------------ internals

    private Entry live(Fact<?> fact, Object subject) {
        Validate.notNull(fact, "fact");
        Map<Object, Entry> about = facts.get(fact);
        if (about == null) {
            return null;
        }
        Object key = subject == null ? NONE : subject;
        Entry entry = about.get(key);
        if (entry != null && entry.expired(tick, nanos.getAsLong())) {
            about.remove(key);
            return null;
        }
        return entry;
    }

    @SuppressWarnings("unchecked")
    private <T> Recollection<T> recollection(Fact<T> fact, Object subject, Entry entry) {
        return new Recollection<>(fact, subject, (T) entry.value, tick - entry.tick,
                (nanos.getAsLong() - entry.nanos) / 1_000_000L, entry.expiry == null);
    }

    private static final class Entry {

        private final Object value;
        private final long tick;
        private final long nanos;
        private final Span expiry;

        private Entry(Object value, long tick, long nanos, Span expiry) {
            this.value = value;
            this.tick = tick;
            this.nanos = nanos;
            this.expiry = expiry;
        }

        private boolean expired(long nowTick, long nowNanos) {
            return expiry != null && expiry.hasPassed(tick, nanos, nowTick, nowNanos);
        }
    }
}
