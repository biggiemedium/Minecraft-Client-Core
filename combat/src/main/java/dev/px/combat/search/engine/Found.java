package dev.px.combat.search.engine;

import dev.px.combat.search.option.Option;
import dev.px.combat.search.option.Trigger;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;

/**
 * What {@link ExplosiveSearch} found: an option, and the spot or explosive it is about.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 * @param <T> the spot or explosive: what your device deals in
 */
public final class Found<E, T> extends Option<E> {

    private final T subject;

    Found(T subject, Vec3 origin, Vec3 aim, Tracked<? extends E> target, double damage, double selfDamage,
          double score, double aimCost, Trigger trigger, boolean own) {
        super(origin, aim, target, damage, selfDamage, score, aimCost, trigger, own);
        this.subject = subject;
    }

    /** @return the spot to place at, or the explosive to set off */
    public T getSubject() {
        return subject;
    }

    @Override
    public String toString() {
        return "Found(" + subject + ": " + facts() + ")";
    }
}
