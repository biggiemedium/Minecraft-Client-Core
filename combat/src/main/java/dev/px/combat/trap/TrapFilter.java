package dev.px.combat.trap;

/**
 * Your own last word on a trap: refuse anything the settings cannot express.
 *
 * <pre>{@code
 * .filter(trap -> trap.isEnclosed() || trap.getMissing().size() <= 4)   // only traps nearly done, or holes
 * }</pre>
 *
 * @param <E> the game's type for who is trapped
 */
@FunctionalInterface
public interface TrapFilter<E> {

    /** @return whether the search may offer {@code trap} */
    boolean accepts(TrapOption<E> trap);
}
