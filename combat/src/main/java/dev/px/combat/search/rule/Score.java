package dev.px.combat.search.rule;

/**
 * How options that pass every threshold are ranked: higher is better.
 *
 * <pre>{@code
 * Score.DAMAGE                 // the most damage to the target
 * Score.balanced(0.5)          // damage to them, minus half the damage to you
 * (them, me) -> them / (me + 1)  // anything else
 * }</pre>
 *
 * <p>The search skips options it can prove cannot win, using the most damage
 * each one could do. That is only sound when a score is never more than the
 * damage to the target, which every score here keeps to; a score that breaks it
 * should turn pruning off with {@code CrystalSearch.Builder#pruning(false)}.
 */
@FunctionalInterface
public interface Score {

    /** The most damage to the target wins; ties go to the least to you. */
    Score DAMAGE = (target, self) -> target;

    /** @return damage to them, minus {@code selfWeight} times damage to you */
    static Score balanced(double selfWeight) {
        return (target, self) -> target - selfWeight * self;
    }

    double score(double targetDamage, double selfDamage);
}
