package dev.px.combat.search.option;

import dev.px.core.util.Validate;

import java.util.List;
import java.util.function.Supplier;

/**
 * An option the search is about to take, shown to your {@code OptionFilter}
 * first: everything an {@link Option} says, and what it would do to everyone the
 * search protects.
 *
 * <p>It has already passed every threshold, anti-suicide and protection, and it
 * beats every other option for the same spot or explosive so far.
 *
 * <p>Damage to protected entities costs raycasting, so it is only worked out when
 * you first ask for it, then kept for every proposal about the same explosion.
 * Ask during the filter call: the world moves on after it.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class Proposal<E> extends Option<E> {

    private final Supplier<List<Harm<E>>> harmed;

    /**
     * @param found the option's facts
     * @param harmed what it does to each protected entity it reaches, worked out when asked
     */
    public Proposal(Option<E> found, Supplier<List<Harm<E>>> harmed) {
        super(found);
        this.harmed = Validate.notNull(harmed, "harmed");
    }

    /**
     * @return each protected entity this explosion would hurt, with how much;
     *         empty when the search protects nobody or reaches none of them
     */
    public List<Harm<E>> getProtected() {
        return harmed.get();
    }

    /** @return the most it would do to any one protected entity; 0 when it reaches none */
    public double getMostProtectedDamage() {
        double most = 0d;
        for (Harm<E> harm : getProtected()) {
            most = Math.max(most, harm.getDamage());
        }
        return most;
    }

    @Override
    public String toString() {
        return "Proposal(" + facts() + ")";
    }
}
