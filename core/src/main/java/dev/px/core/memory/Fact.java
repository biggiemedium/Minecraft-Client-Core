package dev.px.core.memory;

import dev.px.core.util.Validate;

/**
 * A typed name for something worth remembering, in {@link Memory}.
 *
 * <pre>{@code
 * static final Fact<Boolean> UNMINABLE = Fact.of("unminable");     // about a block: the subject
 * static final Fact<Vec3> STASH = Fact.of("stash");                // one value, no subject
 *
 * memory.remember(UNMINABLE, pos, true, Span.seconds(300));
 * memory.remember(STASH, chestPosition);
 * }</pre>
 *
 * <p>Compared by identity, so keep each one in a constant. What a fact means is
 * yours: the library ships none.
 *
 * <p>Immutable.
 *
 * @param <T> the value it holds
 */
public final class Fact<T> {

    private final String name;

    private Fact(String name) {
        this.name = name;
    }

    public static <T> Fact<T> of(String name) {
        return new Fact<>(Validate.notBlank(name, "name"));
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "Fact(" + name + ")";
    }
}
