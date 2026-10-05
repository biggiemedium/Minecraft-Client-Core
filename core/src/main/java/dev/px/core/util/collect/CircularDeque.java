package dev.px.core.util.collect;

/**
 * A {@link CircularQueue} that can be written and read from both ends.
 *
 * <p>The extra end earns its keep wherever recency matters more than order of
 * arrival: an undo stack that only needs the last twenty actions, a "recently
 * used" list where a repeat visit should jump back to the front, a rotation
 * history read newest-first.
 *
 * <p>Eviction follows whichever end is being written. {@link #addLast} drops the
 * oldest entry, exactly as {@link CircularQueue#add} does; {@link #addFirst}
 * drops the newest. Both keep the deque at capacity rather than refusing the
 * write, because the point of a bounded history is that it never blocks the
 * thing producing it.
 *
 * <p><b>Complexity.</b> All four ends &mdash; {@link #addFirst},
 * {@link #addLast}, {@link #pollFirst()} and {@link #pollLast()} &mdash; are O(1),
 * as are the reads inherited from {@link CircularQueue}.
 *
 * <p>Not thread-safe.
 *
 * @param <E> the element type
 */
public final class CircularDeque<E> extends CircularQueue<E> {

    private CircularDeque(int capacity) {
        super(capacity);
    }

    public static <E> CircularDeque<E> of(int capacity) {
        return new CircularDeque<>(capacity);
    }

    /**
     * Prepends {@code element}, evicting the newest entry if the deque is full.
     *
     * @return the evicted entry, or {@code null} if there was room
     */
    @SuppressWarnings("unchecked")
    public E addFirst(E element) {
        E evicted = null;
        if (size == elements.length) {
            int tail = slot(size - 1);
            evicted = (E) elements[tail];
            elements[tail] = null;
            size--;
        }
        head = previous(head);
        elements[head] = element;
        size++;
        return evicted;
    }

    /** Appends {@code element}. Identical to {@link #add}, named for symmetry. */
    public E addLast(E element) {
        return add(element);
    }

    /** @return the oldest entry, removing it, or {@code null} when empty. */
    public E pollFirst() {
        return poll();
    }

    /** @return the newest entry, removing it, or {@code null} when empty. */
    @SuppressWarnings("unchecked")
    public E pollLast() {
        if (size == 0) {
            return null;
        }
        int tail = slot(size - 1);
        E value = (E) elements[tail];
        elements[tail] = null;
        size--;
        return value;
    }

    public E peekFirst() {
        return peek();
    }
}
