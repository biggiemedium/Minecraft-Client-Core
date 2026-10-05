package dev.px.core.util.collect;

import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * A fixed-capacity FIFO ring buffer that evicts its oldest entry when full.
 *
 * <p>The structure a client wants for every "last N of something" &mdash; chat
 * history, sent packets, the frame times behind an FPS graph, a rubber-band log.
 * A plain {@link java.util.ArrayList} used that way grows without bound until
 * someone remembers to trim it, and trimming from the front of an array list
 * copies every remaining element on each insert.
 *
 * <p>Here the array is allocated once and never grows: adding to a full queue
 * overwrites the oldest entry in place, so a queue polled once a second and
 * pushed sixty times a second still allocates nothing after construction. That
 * is the property that makes it safe to use from the render loop.
 *
 * <p>Iteration and {@link #get(int)} run oldest-first, which is the order a graph
 * or a scrollback wants to draw in.
 *
 * <p><b>Complexity.</b> {@link #add}, {@link #poll()}, {@link #peek()},
 * {@link #peekLast()} and {@link #get(int)} are O(1); {@link #toList()},
 * {@link #stream()} and iteration are O(n); {@link #clear()} is O(capacity).
 *
 * <p>Not thread-safe. A queue shared with {@link dev.px.core.concurrent.ThreadService}
 * work needs external synchronisation.
 *
 * @param <E> the element type
 */
public class CircularQueue<E> implements Iterable<E> {

    /** Storage. Indices are logical; {@link #slot(int)} maps them onto this. */
    protected final Object[] elements;

    /** Index in {@link #elements} of the oldest entry. */
    protected int head;

    protected int size;

    protected CircularQueue(int capacity) {
        Validate.check(capacity > 0, "capacity must be positive");
        this.elements = new Object[capacity];
    }

    public static <E> CircularQueue<E> of(int capacity) {
        return new CircularQueue<>(capacity);
    }

    // ------------------------------------------------------------- writing

    /**
     * Appends {@code element}, evicting the oldest entry if the queue is full.
     *
     * @return the evicted entry, or {@code null} if there was room. Checking the
     *         return value is how a caller notices data is being dropped, which
     *         a silently overwriting buffer otherwise hides
     */
    @SuppressWarnings("unchecked")
    public E add(E element) {
        if (size < elements.length) {
            elements[slot(size++)] = element;
            return null;
        }
        E evicted = (E) elements[head];
        elements[head] = element;
        head = next(head);
        return evicted;
    }

    /** Adds several entries in order. Later ones may evict earlier ones. */
    @SafeVarargs
    public final void addAll(E... entries) {
        for (E entry : entries) {
            add(entry);
        }
    }

    /** @return the oldest entry, removing it, or {@code null} when empty. */
    @SuppressWarnings("unchecked")
    public E poll() {
        if (size == 0) {
            return null;
        }
        E value = (E) elements[head];
        elements[head] = null;
        head = next(head);
        size--;
        return value;
    }

    public void clear() {
        Arrays.fill(elements, null);
        head = 0;
        size = 0;
    }

    // ------------------------------------------------------------- reading

    /** @return the oldest entry without removing it, or {@code null} when empty. */
    @SuppressWarnings("unchecked")
    public E peek() {
        return size == 0 ? null : (E) elements[head];
    }

    /** @return the newest entry without removing it, or {@code null} when empty. */
    @SuppressWarnings("unchecked")
    public E peekLast() {
        return size == 0 ? null : (E) elements[slot(size - 1)];
    }

    /**
     * @param index 0 for the oldest entry, {@code size() - 1} for the newest
     * @throws IndexOutOfBoundsException if the index is outside the live entries
     */
    @SuppressWarnings("unchecked")
    public E get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " outside 0.." + (size - 1));
        }
        return (E) elements[slot(index)];
    }

    public int size() {
        return size;
    }

    /** @return the fixed capacity. Never changes after construction. */
    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** @return whether the next {@link #add} will evict something. */
    public boolean isFull() {
        return size == elements.length;
    }

    /** @return a snapshot copy, oldest first. Safe to hold; the queue keeps moving. */
    public List<E> toList() {
        List<E> copy = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            copy.add(get(i));
        }
        return copy;
    }

    public Stream<E> stream() {
        return toList().stream();
    }

    /**
     * @return an iterator over the live entries, oldest first.
     *
     * <p>Unlike the collections framework this does not fail fast on concurrent
     * modification: the buffer is a fixed window that is expected to be written
     * while something else reads it. Iterate {@link #toList()} instead when a
     * stable view matters.
     */
    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {

            private int cursor;

            @Override
            public boolean hasNext() {
                return cursor < size;
            }

            @Override
            public E next() {
                if (cursor >= size) {
                    throw new NoSuchElementException();
                }
                return get(cursor++);
            }
        };
    }

    @Override
    public String toString() {
        return toList().toString();
    }

    // ------------------------------------------------------------- indexing

    /**
     * Maps a logical index (0 = oldest) onto a slot in {@link #elements}:
     * {@code slot = (head + index) mod capacity}.
     */
    protected final int slot(int index) {
        return (head + index) % elements.length;
    }

    protected final int next(int slot) {
        return (slot + 1) % elements.length;
    }

    protected final int previous(int slot) {
        return (slot - 1 + elements.length) % elements.length;
    }
}
