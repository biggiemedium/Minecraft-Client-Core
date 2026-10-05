package dev.px.combat.search.option;

import dev.px.core.entity.Tracked;

/**
 * Which crystal to break, and why: who it is for, and what it would do to them
 * and to you.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class BreakOption<E> extends Option<E> {

    private final Tracked<?> crystal;

    public BreakOption(Tracked<?> crystal, Option<E> found) {
        super(found);
        this.crystal = crystal;
    }

    /** @return the crystal to attack; {@code getCrystal().get()} is the game's own entity */
    public Tracked<?> getCrystal() {
        return crystal;
    }

    @Override
    public String toString() {
        return "BreakOption(" + crystal.getPosition() + ": " + facts() + ")";
    }
}
