package dev.px.core.util.collect;

import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A map that forgets whatever was used least recently once it is full.
 *
 * <p>Clients cache constantly: a resolved skin per player, a measured string
 * width per label, a parsed response per request. Every one of those maps is
 * unbounded by default, and the leak is invisible because each entry is tiny
 * &mdash; a HUD element caching text widths gains an entry per distinct string
 * and runs for eight hours.
 *
 * <p>A bound turns that into a fixed cost. Eviction is by least-recent *use*, not
 * least-recent insert, which is the difference that matters: the twenty players
 * actually on screen stay cached however long ago they were first seen.
 *
 * <p>{@link #onEvict} exists for the caches that hold something which has to be
 * released. A texture handle dropped from a cache without being freed leaks GPU
 * memory that no heap profiler will show.
 *
 * <pre>{@code
 * private final LruCache<String, Texture> heads =
 *         LruCache.of(64).onEvict((name, texture) -> texture.dispose());
 *
 * Texture head = heads.computeIfAbsent(name, this::downloadHead);
 * }</pre>
 *
 * <p><b>Complexity.</b> {@link #get}, {@link #put}, {@link #remove},
 * {@link #contains} and {@link #computeIfAbsent} are O(1) expected: a hash lookup
 * plus a constant relink of the access list. Eviction is O(1) too, so a full
 * cache costs no more per write than an empty one. {@link #keys()} and
 * {@link #entries()} wrap a view in O(1); walking it is O(n).
 *
 * <p>Not thread-safe. Wrap it or give each thread its own.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class LruCache<K, V> {

    private final Map<K, V> entries;
    private final int maxSize;

    private BiConsumer<K, V> evictionListener;

    private LruCache(int maxSize) {
        Validate.check(maxSize > 0, "maxSize must be positive");
        this.maxSize = maxSize;
        // accessOrder = true is what makes this an LRU rather than a FIFO.
        this.entries = new LinkedHashMap<K, V>(Math.min(maxSize, 16), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                if (size() <= LruCache.this.maxSize) {
                    return false;
                }
                if (evictionListener != null) {
                    evictionListener.accept(eldest.getKey(), eldest.getValue());
                }
                return true;
            }
        };
    }

    public static <K, V> LruCache<K, V> of(int maxSize) {
        return new LruCache<>(maxSize);
    }

    /**
     * Registers a callback run for every entry eviction pushes out.
     *
     * <p>Not called by {@link #remove} or {@link #clear()}: those are the caller
     * deliberately dropping an entry they already know about, and firing a
     * release hook underneath them would double-free.
     */
    public LruCache<K, V> onEvict(BiConsumer<K, V> listener) {
        this.evictionListener = listener;
        return this;
    }

    /** @return the cached value, or {@code null}. Counts as a use. */
    public V get(K key) {
        return entries.get(key);
    }

    /** @return the previous value for {@code key}, or {@code null}. */
    public V put(K key, V value) {
        return entries.put(key, value);
    }

    /**
     * @return the cached value, computing and storing it on a miss
     *
     * <p>The form to reach for: it is one lookup instead of the
     * check-then-put pair, and it cannot race with itself into two computations
     * of the same key on one thread.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> factory) {
        V existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        V created = factory.apply(key);
        if (created != null) {
            entries.put(key, created);
        }
        return created;
    }

    public V remove(K key) {
        return entries.remove(key);
    }

    public boolean contains(K key) {
        return entries.containsKey(key);
    }

    public int size() {
        return entries.size();
    }

    public int capacity() {
        return maxSize;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    /** @return a live unmodifiable view, in least-recently-used order. */
    public Set<Map.Entry<K, V>> entries() {
        return Collections.unmodifiableMap(entries).entrySet();
    }

    /** @return the keys, least recently used first. The next eviction is the first. */
    public Set<K> keys() {
        return Collections.unmodifiableMap(entries).keySet();
    }

    @Override
    public String toString() {
        return "LruCache[" + entries.size() + "/" + maxSize + "]";
    }
}
