package dev.px.combat.search.engine;

/**
 * Sees every exact damage estimate a search makes, and what became of it.
 *
 * <pre>{@code
 * Map<Object, Double> seen = new HashMap<>();
 * CrystalSearch.<LivingEntity>builder()
 *         ...
 *         .listener(judged -> seen.merge(judged.getSubject(), judged.getDamage(), Math::max))   // draw these
 *         .build();
 * }</pre>
 *
 * <p>Only what was estimated: spots branch and bound skipped were never worked
 * out, so a listener sees the few that mattered, not the whole map. For every
 * spot, turn {@code pruning} off while you look. Called on the game thread,
 * during the search; keep it cheap, and do not search from inside it.
 *
 * @param <E> the game's type for what can be hurt
 */
@FunctionalInterface
public interface SearchListener<E> {

    void judged(Judgement<E> judgement);
}
