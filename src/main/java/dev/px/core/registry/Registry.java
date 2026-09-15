package dev.px.core.registry;

import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ordered, name-and-type addressable collection of {@link Named} entries.
 *
 * <p>This exists because the old client had four hand-written copies of the same
 * class &mdash; module, element, command, and colour managers each with their own
 * {@code ArrayList}, {@code Add()}, {@code getByName()} and {@code getByClass()}.
 * Subclasses now add only the behaviour that is actually specific to them.
 *
 * <p>Lookups by name are case-insensitive, which is what commands and config
 * files need; lookups by class return the first assignable entry, so asking for
 * a base type gives you the concrete instance registered under it.
 *
 * @param <T> the registered type
 */
public class Registry<T extends Named> implements Iterable<T> {

    private final Map<String, T> byName = new LinkedHashMap<>();
    private final List<T> ordered = new ArrayList<>();

    /**
     * Registers an entry.
     *
     * @return the entry, so callers can register and keep a reference in one line
     * @throws IllegalArgumentException if the name is blank or already taken
     */
    public <E extends T> E register(E entry) {
        Validate.notNull(entry, "entry");
        String name = Validate.notBlank(entry.getName(), "entry name");
        String key = key(name);
        T existing = byName.get(key);
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate " + describe() + " name '" + name + "': "
                    + existing.getClass().getName() + " is already registered");
        }
        byName.put(key, entry);
        ordered.add(entry);
        onRegistered(entry);
        return entry;
    }

    /** Registers several entries in order. */
    @SafeVarargs
    public final void registerAll(T... entries) {
        for (T entry : entries) {
            register(entry);
        }
    }

    public final void registerAll(Collection<? extends T> entries) {
        entries.forEach(this::register);
    }

    public boolean unregister(T entry) {
        if (entry == null || !ordered.remove(entry)) {
            return false;
        }
        byName.remove(key(entry.getName()));
        onUnregistered(entry);
        return true;
    }

    public Optional<T> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(key(name)));
    }

    /** @return the entry with this name, or {@code null}. Prefer {@link #find} in new code. */
    public T get(String name) {
        return find(name).orElse(null);
    }

    /** @return the first entry assignable to {@code type}, or {@code null}. */
    public <E extends T> E get(Class<E> type) {
        for (T entry : ordered) {
            if (type.isInstance(entry)) {
                return type.cast(entry);
            }
        }
        return null;
    }

    /**
     * @throws IllegalStateException if nothing of that type is registered. Use this
     *         where a missing entry is a programming error rather than a user input
     */
    public <E extends T> E require(Class<E> type) {
        E found = get(type);
        if (found == null) {
            throw new IllegalStateException("No " + describe() + " of type " + type.getName() + " is registered");
        }
        return found;
    }

    public boolean contains(String name) {
        return name != null && byName.containsKey(key(name));
    }

    /**
     * @return a live, unmodifiable view of the entries, in order.
     *
     * <p>A view, not a copy: it reflects later registrations, and iterating it
     * while registering or unregistering throws. Use {@link #clear()} to empty
     * the registry, and copy the list first if you must mutate during a walk.
     */
    public List<T> all() {
        return Collections.unmodifiableList(ordered);
    }

    /**
     * Removes every entry, notifying {@link #onUnregistered} for each.
     *
     * <p>Exists because the obvious way to write it &mdash; iterating
     * {@link #all()} and unregistering &mdash; walks a live view and throws
     * {@link java.util.ConcurrentModificationException}. Config loads that
     * replace a whole list wholesale need this.
     */
    public void clear() {
        List<T> snapshot = new ArrayList<>(ordered);
        ordered.clear();
        byName.clear();
        for (T entry : snapshot) {
            onUnregistered(entry);
        }
    }

    public List<T> where(Predicate<? super T> filter) {
        return ordered.stream().filter(filter).collect(Collectors.toList());
    }

    public Stream<T> stream() {
        return ordered.stream();
    }

    public int size() {
        return ordered.size();
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /** Re-orders entries in place. Call after all registrations, e.g. to sort alphabetically. */
    public void sort(Comparator<? super T> comparator) {
        ordered.sort(comparator);
    }

    /** Sorts by name. The common case, so it gets a shorthand. */
    public void sortByName() {
        sort(Comparator.comparing(Named::getName, String.CASE_INSENSITIVE_ORDER));
    }

    @Override
    public java.util.Iterator<T> iterator() {
        return all().iterator();
    }

    /** Hook for subclasses to wire an entry up (subscribe it, index it) as it arrives. */
    protected void onRegistered(T entry) {
    }

    protected void onUnregistered(T entry) {
    }

    /** Used in error messages. Override to say "module" or "command" instead of the class name. */
    protected String describe() {
        return getClass().getSimpleName();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
