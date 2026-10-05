package dev.px.combat.search.option;

import dev.px.core.entity.Tracked;

import java.util.Locale;

/**
 * What an option would do to one entity the search protects: who, how much, and
 * how much they had to lose.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class Harm<E> {

    private final Tracked<? extends E> entity;
    private final double damage;
    private final double pool;

    public Harm(Tracked<? extends E> entity, double damage, double pool) {
        this.entity = entity;
        this.damage = damage;
        this.pool = pool;
    }

    /** @return who would be hurt; {@code getEntity().get()} is the game's own entity */
    public Tracked<? extends E> getEntity() {
        return entity;
    }

    /** @return what the explosion would do to them, after mitigation */
    public double getDamage() {
        return damage;
    }

    /** @return what they can still take, from your {@code Vitals}; NaN when unknown or untrusted */
    public double getPool() {
        return pool;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Harm(%s: %.2f of %.2f)", entity.getPosition(), damage, pool);
    }
}
