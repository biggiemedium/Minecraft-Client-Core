package dev.px.combat.search.rule;

import dev.px.combat.search.option.Proposal;

/**
 * Your own last word on an option: refuse anything the thresholds cannot
 * express. It sees the target, the damage to them and to you, why the option is
 * worth it, and what it would do to everyone the search protects.
 *
 * <pre>{@code
 * .filter(p -> !Game.isInHole(p.getTarget().get()) || p.getTrigger() == Trigger.LETHAL)
 * .filter(p -> p.getMostProtectedDamage() < p.getDamage() / 2)   // never hurt a friend half as much as them
 * }</pre>
 *
 * <p>A filter is asked only about an option that has passed every threshold and
 * would be chosen, so it runs a handful of times a search, not once per spot.
 * Several filters are all required. Refusing never changes which spots the
 * search can skip, so branch and bound stays exact.
 *
 * @param <E> the game's type for what can be hurt
 */
@FunctionalInterface
public interface OptionFilter<E> {

    /** @return whether the search may take {@code proposal} */
    boolean accepts(Proposal<E> proposal);
}
