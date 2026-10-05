package dev.px.core.util.collect;

import dev.px.core.util.Validate;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A map whose entries go stale after a fixed time.
 *
 * <p>{@link LruCache} bounds how much is remembered; this bounds how long. They
 * answer different questions, and using the wrong one shows: a player's ping
 * cached by size is never wrong about memory and always wrong about the ping.
 *
 * <p>The use is anything fetched from outside the client. A now-playing track, a
 * UUID lookup, a version check &mdash; each is expensive enough to be worth
 * caching and wrong enough after a minute to be worth expiring. Without a TTL
 * the alternatives are re-requesting every frame or showing the first answer
 * forever.
 *
 * <p>Expiry is lazy: an entry is dropped when it is next looked at, not by a
 * timer thread. A cache nothing reads costs nothing, and there is no background
 * thread to shut down. {@link #purge()} forces the sweep when the memory matters
 * more than the wake-up.
 *
 * <pre>{@code
 * private final ExpiringCache<UUID, String> names = ExpiringCache.of(60_000L);
 *
 * String name = names.get(id);
 * if (name == null) {
 *     Core.threads().submit(() -> names.put(id, lookUp(id)));
 * }
 * }</pre>
 *
 * <p><b>Complexity.</b> {@link #get}, {@link #put} and {@link #remove} are O(1)
 * expected, expiry being one comparison on the way past:
 *
 * <pre>
 * expired = now - storedAt &gt;= lifetime
 * </pre>
 *
 * <p>{@link #purge()} sweeps everything and is O(n), which is why it is a method
 * rather than something the cache does on its own.
 *
 * <p>Not thread-safe.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class ExpiringCache<K, V> {

    private final Map<K, Entry<V>> entries = new HashMap<>();
    private final long lifetimeNanos;
    private final LongSupplier clock;

    private ExpiringCache(long lifetimeMillis, LongSupplier clock) {
        Validate.check(lifetimeMillis >= 0L, "lifetime must not be negative");
        this.lifetimeNanos = lifetimeMillis * 1_000_000L;
        this.clock = clock;
    }

    /** @param lifetimeMillis how long an entry stays valid after it is written */
    public static <K, V> ExpiringCache<K, V> of(long lifetimeMillis) {
        return new ExpiringCache<>(lifetimeMillis, System::nanoTime);
    }

    /**
     * A cache reading time from {@code nanoClock} instead of the system clock.
     *
     * <p>The seam exists so expiry can be tested without sleeping, and so a
     * caller with its own clock &mdash; a replay, a fixed tick counter &mdash; can
     * supply it. Everything else in Core times itself against
     * {@link System#nanoTime()} for the reason given on
     * {@link dev.px.core.math.Stopwatch}: a wall clock can move backwards.
     */
    public static <K, V> ExpiringCache<K, V> of(long lifetimeMillis, LongSupplier nanoClock) {
        return new ExpiringCache<>(lifetimeMillis, Validate.notNull(nanoClock, "nanoClock"));
    }

    /** @return the value, or {@code null} if it was never stored or has expired. */
    public V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry)) {
            entries.remove(key);
            return null;
        }
        return entry.value;
    }

    /** Stores a value, restarting its lifetime. */
    public void put(K key, V value) {
        entries.put(key, new Entry<>(value, clock.getAsLong()));
    }

    /**
     * @return the cached value, computing and storing it if it is missing or stale
     *
     * <p>Only for values cheap enough to produce inline. Anything that touches the
     * network belongs on {@link dev.px.core.concurrent.ThreadService}, with the
     * result handed back through {@link #put}: this method blocks whoever calls
     * it, which on the render thread is a stutter.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> factory) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        V created = factory.apply(key);
        if (created != null) {
            put(key, created);
        }
        return created;
    }

    /** @return whether a live entry exists. Drops the entry if it has expired. */
    public boolean contains(K key) {
        return get(key) != null;
    }

    public V remove(K key) {
        Entry<V> removed = entries.remove(key);
        return removed == null ? null : removed.value;
    }

    /** @return how long {@code key} has left, in milliseconds, or 0 if it is gone or stale. */
    public long remainingMillis(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null || isExpired(entry)) {
            return 0L;
        }
        return (lifetimeNanos - (clock.getAsLong() - entry.storedAt)) / 1_000_000L;
    }

    /**
     * Drops every expired entry.
     *
     * @return how many were removed
     *
     * <p>Only needed for a cache that is written far more often than it is read;
     * otherwise the lazy path already keeps it bounded.
     */
    public int purge() {
        int removed = 0;
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().getValue())) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /** @return how many entries are held, expired ones included. */
    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    public long getLifetimeMillis() {
        return lifetimeNanos / 1_000_000L;
    }

    private boolean isExpired(Entry<V> entry) {
        return clock.getAsLong() - entry.storedAt >= lifetimeNanos;
    }

    @Override
    public String toString() {
        return "ExpiringCache[" + entries.size() + " entries, " + getLifetimeMillis() + "ms]";
    }

    /** A value and when it was written. */
    private static final class Entry<V> {

        private final V value;
        private final long storedAt;

        private Entry(V value, long storedAt) {
            this.value = value;
            this.storedAt = storedAt;
        }
    }
}
