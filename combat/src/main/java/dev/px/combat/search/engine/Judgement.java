package dev.px.combat.search.engine;

import dev.px.combat.search.option.Trigger;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;

import java.util.Locale;

/**
 * One exact damage estimate a search made, and what became of it: for drawing
 * the damage a search saw, and for seeing why it chose nothing.
 *
 * <p>Immutable; handed to a {@link SearchListener} as it is made.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class Judgement<E> {

    /** What became of one estimate. */
    public enum Verdict {

        /** It passed every check and became the best for its spot or explosive so far. */
        PASSED,

        /** It did not do enough: below every threshold, or no more than the target already takes this tick. */
        TOO_WEAK,

        /** It would leave you within the anti-suicide margin: nothing at this spot is taken. */
        SUICIDAL,

        /** It would hurt someone the search protects too much: nothing at this spot is taken. */
        ENDANGERS,

        /** It would hurt you more than the self-damage cap allows. */
        SELF_CAP,

        /** It passed, but something already found for the same spot ranks higher. */
        OUTRANKED,

        /** It passed, and one of your filters refused it. */
        FILTERED
    }

    private final boolean placing;
    private final Object subject;
    private final Vec3 origin;
    private final Tracked<? extends E> target;
    private final double damage;
    private final double selfDamage;
    private final Trigger trigger;
    private final Verdict verdict;

    Judgement(boolean placing, Object subject, Vec3 origin, Tracked<? extends E> target, double damage,
              double selfDamage, Trigger trigger, Verdict verdict) {
        this.placing = placing;
        this.subject = subject;
        this.origin = origin;
        this.target = target;
        this.damage = damage;
        this.selfDamage = selfDamage;
        this.trigger = trigger;
        this.verdict = verdict;
    }

    /** @return whether it is about a place to put an explosive; false for one already in the world */
    public boolean isPlacing() {
        return placing;
    }

    /** @return the spot or explosive, as the device deals in it: a crystal's base, a {@code Bed}, a crystal */
    public Object getSubject() {
        return subject;
    }

    /** @return where the explosion would come from */
    public Vec3 getOrigin() {
        return origin;
    }

    public Tracked<? extends E> getTarget() {
        return target;
    }

    public double getDamage() {
        return damage;
    }

    /** @return what it would do to you; NaN when the search had no need to work it out */
    public double getSelfDamage() {
        return selfDamage;
    }

    /** @return why it was worth it; null when it was not */
    public Trigger getTrigger() {
        return trigger;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Judgement(%s %s: %.2f to target, %.2f to self, %s)",
                placing ? "place" : "use", subject, damage, selfDamage, verdict);
    }
}
