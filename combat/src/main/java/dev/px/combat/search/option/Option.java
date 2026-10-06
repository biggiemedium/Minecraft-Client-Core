package dev.px.combat.search.option;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;

import java.util.Locale;

/**
 * What every search result says, whatever the explosive: where it explodes
 * from, where to look to act on it, who it is for, what it would do to them and
 * to you, and why it was worth it.
 *
 * <p>Options are ranked by {@link #getRank()} &mdash; the score less what turning
 * to look costs &mdash; then by the least damage to you.
 *
 * <p>Immutable.
 *
 * @param <E> the game's type for what can be hurt
 */
public abstract class Option<E> {

    private final Vec3 origin;
    private final Vec3 aim;
    private final Tracked<? extends E> target;
    private final double damage;
    private final double selfDamage;
    private final double score;
    private final double aimCost;
    private final Trigger trigger;
    private final boolean own;

    protected Option(Vec3 origin, Vec3 aim, Tracked<? extends E> target, double damage, double selfDamage,
                     double score, double aimCost, Trigger trigger, boolean own) {
        this.origin = origin;
        this.aim = aim;
        this.target = target;
        this.damage = damage;
        this.selfDamage = selfDamage;
        this.score = score;
        this.aimCost = aimCost;
        this.trigger = trigger;
        this.own = own;
    }

    /** The same facts as {@code found}: for a result that adds what to act on. */
    protected Option(Option<E> found) {
        this(found.origin, found.aim, found.target, found.damage, found.selfDamage, found.score, found.aimCost,
                found.trigger, found.own);
    }

    /** @return where it would explode from */
    public Vec3 getOrigin() {
        return origin;
    }

    /**
     * @return where to look to act on it: the point your {@code AimCost} was asked
     *         about, for your rotation manager to turn to
     */
    public Vec3 getAim() {
        return aim;
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

    /** @return what your {@code Score} made of it, before aiming */
    public double getScore() {
        return score;
    }

    /** @return what your {@code AimCost} said turning to {@link #getAim()} is worth; 0 without one */
    public double getAimCost() {
        return aimCost;
    }

    /** @return what it was ranked by: the score less the aim cost */
    public double getRank() {
        return score - aimCost;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    /**
     * @return whether it is about an explosive already in the world that showed up
     *         where you placed one: yours. Always false for a place to put one
     */
    public boolean isOwn() {
        return own;
    }

    /** @return whether this ranks above {@code other}: a higher rank, or the same with less damage to you */
    public boolean beats(Option<?> other) {
        if (other == null) {
            return true;
        }
        double rank = getRank();
        double otherRank = other.getRank();
        return rank > otherRank || (rank == otherRank && selfDamage < other.selfDamage);
    }

    /** @return the damage, self-damage, trigger and any aim cost, for {@code toString} */
    protected String facts() {
        String facts = String.format(Locale.ROOT, "%.2f to target, %.2f to self, %s", damage, selfDamage, trigger);
        return aimCost == 0d ? facts : facts + String.format(Locale.ROOT, ", aim %.2f", aimCost);
    }
}
