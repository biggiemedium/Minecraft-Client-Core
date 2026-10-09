package dev.px.combat.fight;

import dev.px.core.flow.StopReason;

/**
 * What every part of a {@link Fight} shares: whether it answers or drives, and
 * being told when the fight starts and stops.
 *
 * <ul>
 *   <li>A part that <b>answers</b> &mdash; the default &mdash; says what it wants
 *       each tick, and the fight step claims it through the flow, at the flow's
 *       priority. Pausing, reflexes and priorities then work without the part
 *       knowing about them.
 *   <li>A part that <b>drives</b> acts through your client's own code &mdash; its
 *       own rotation manager, packets sent directly &mdash; and the step claims
 *       nothing for it. It is still asked each tick, so it knows the fight is on,
 *       and {@link #stop} is how it learns the fight was paused or is over: it
 *       must stop acting there, or a pause will not stop it.
 * </ul>
 *
 * <p>Called on the game thread.
 *
 * @param <E> the game's type for what is fought
 */
public interface Part<E> {

    /** @return whether this part acts itself, rather than answering for the step to claim */
    default boolean drives() {
        return false;
    }

    /**
     * Called as the fight starts, and again as it resumes after a pause:
     * {@code b.context().isResuming()} says which.
     */
    default void start(Bout<? extends E> b) {
    }

    /** Called as the fight stops: finished, failed, paused or cancelled. A part that drives stops acting here. */
    default void stop(Bout<? extends E> b, StopReason why) {
    }
}
