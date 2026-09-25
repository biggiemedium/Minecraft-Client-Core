package dev.px.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gives each category, tag and attribute the developer declares a small,
 * permanent number, the first time Core sees it.
 *
 * <p>That number is what makes an open vocabulary as cheap as a closed one: a
 * category indexes an array of per-category lists, a tag is one bit of a word,
 * an attribute one slot of a {@code double[]}. The developer never sees it.
 *
 * <p>Numbers are handed out once and never reused, so a selector can resolve its
 * tags when it is built and trust them forever. Thread-safe.
 */
final class Slots {

    private static final Slots CATEGORIES = new Slots();
    private static final Slots TAGS = new Slots();
    private static final Slots ATTRIBUTES = new Slots();

    private final Map<Object, Integer> slots = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger();

    private Slots() {
    }

    static int of(EntityCategory category) {
        return CATEGORIES.slot(category);
    }

    static int of(EntityTag tag) {
        return TAGS.slot(tag);
    }

    static int of(EntityAttribute attribute) {
        return ATTRIBUTES.slot(attribute);
    }

    private int slot(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("null is not a valid category, tag or attribute");
        }
        Integer existing = slots.get(key);
        if (existing != null) {
            return existing;
        }
        return slots.computeIfAbsent(key, ignored -> next.getAndIncrement());
    }
}
