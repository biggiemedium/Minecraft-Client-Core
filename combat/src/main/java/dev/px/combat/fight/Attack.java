package dev.px.combat.fight;

/**
 * The aura of a {@link Fight}: when to swing, and at what.
 *
 * <pre>{@code
 * // your killaura's brain, as a plain object your KillAura module calls too
 * public final class MyAura implements Attack<EntityPlayer> {
 *     public Strike tick(Bout<? extends EntityPlayer> b) {
 *         Tracked<? extends EntityPlayer> target = b.target();
 *         if (target.distanceToBox(b.eyes()) > reach.get()) {
 *             return Strike.at(target.getCenter());                     // keep looking, do not swing
 *         }
 *         Strike look = Strike.at(target.aimPoint(b.eyes(), 0.1));
 *         return cooldown.ready() ? look.click(Click.ATTACK).whenFacing(target.getBox()) : look;
 *     }
 *
 *     public void struck(Bout<? extends EntityPlayer> b, Strike strike) {
 *         cooldown.reset();                                             // the click went out
 *     }
 * }
 * }</pre>
 *
 * <p>Asked once a tick for a {@link Strike}. Reach, the cooldown between
 * swings, and whether something is in the way are facts about the game, so they
 * live here, in your code: the library never assumes one.
 *
 * <p>An aura that cannot be split from your client's own rotations and packets
 * {@linkplain Part#drives drives} instead: it acts itself in {@link #tick}, and
 * its strike is ignored unless it {@linkplain Strike#failed failed}.
 *
 * @param <E> the game's type for what is fought
 */
public interface Attack<E> extends Part<E> {

    /** @return what to do this tick; null for nothing */
    Strike tick(Bout<? extends E> b);

    /**
     * Called when the step clicked or held the strike's button this tick: the
     * head looked at its box, if it named one. Record a swing here, not in
     * {@link #tick}, so a click that never went out is never counted.
     */
    default void struck(Bout<? extends E> b, Strike strike) {
    }
}
