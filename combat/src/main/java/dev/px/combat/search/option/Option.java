package dev.px.combat.search.option;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;

import java.util.Locale;

/**
 * What every search result says, whatever the explosive: where it explodes
 * from, who it is for, what it would do to them and to you, and why it was
 * worth it.
 *
 * <p>Options are ranked by {@link #getScore()}, then by the least damage to you.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public abstract class Option<E> {

    private final Vec3 origin;
    private final Tracked<? extends E> target;
    private final double damage;
    private final double selfDamage;
    private final double score;
    private final Trigger trigger;

    protected Option(Vec3 origin, Tracked<? extends E> target, double damage, double selfDamage,
                     double score, Trigger trigger) {
        this.origin = origin;
        this.target = target;
        this.damage = damage;
        this.selfDamage = selfDamage;
        this.score = score;
        this.trigger = trigger;
    }

    /** The same facts as {@code found}: for a result that adds what to act on. */
    protected Option(Option<E> found) {
        this(found.origin, found.target, found.damage, found.selfDamage, found.score, found.trigger);
    }

    /** @return where it would explode from */
    public Vec3 getOrigin() {
        return origin;
    }

    /** @return who it is for */
    public Tracked<? extends E> getTarget() {
        return target;
    }

    /** @return what it would do to the target */
    public double getDamage() {
        return damage;
    }

    /** @return what it would do to you */
    public double getSelfDamage() {
        return selfDamage;
    }

    public double getScore() {
        return score;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    /** @return whether this ranks above {@code other}: a higher score, or the same with less damage to you */
    public boolean beats(Option<?> other) {
        return other == null || score > other.score || (score == other.score && selfDamage < other.selfDamage);
    }

    /** @return the damage, self-damage and trigger, for {@code toString} */
    protected String facts() {
        return String.format(Locale.ROOT, "%.2f to target, %.2f to self, %s", damage, selfDamage, trigger);
    }
}
