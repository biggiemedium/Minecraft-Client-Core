package dev.px.combat.hole;

/**
 * Your own last word on a hole to fill: refuse anything the settings cannot
 * express.
 *
 * <pre>{@code
 * .filter(fill -> fill.getHole().isSafe() || enemyHealth(fill.getEnemy()) < 10)   // only bother with weak ones
 * .filter(fill -> fill.getTiming() != FillOption.Timing.LATE)                     // never chase
 * }</pre>
 *
 * <p>Asked about each hole that passed every setting, before ranking. Several
 * filters are all required.
 *
 * @param <E> the game's type for who is denied
 */
@FunctionalInterface
public interface FillFilter<E> {

    /** @return whether the search may offer {@code fill} */
    boolean accepts(FillOption<E> fill);
}
