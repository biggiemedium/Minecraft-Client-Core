package dev.px.core.flow;

import dev.px.core.util.Validate;

/**
 * A typed name for a piece of a flow's own data.
 *
 * <pre>{@code
 * static final Key<Tracked<Player>> TARGET = Key.of("target");
 *
 * c.put(TARGET, found);
 * Tracked<Player> target = c.get(TARGET);
 * }</pre>
 *
 * <p>Scoped to one flow: two flows using the same key each have their own value.
 * Values survive pauses and rewinds, and go when the flow ends. Keys are compared
 * by identity, so keep each one in a constant. For data every flow shares, see
 * {@link dev.px.core.memory.Memory}.
 *
 * <p>Immutable.
 *
 * @param <T> what it holds
 */
public final class Key<T> {

    private final String name;

    private Key(String name) {
        this.name = name;
    }

    public static <T> Key<T> of(String name) {
        Validate.notBlank(name, "name");
        return new Key<>(name);
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "Key(" + name + ")";
    }
}
