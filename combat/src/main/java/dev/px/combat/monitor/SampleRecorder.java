package dev.px.combat.monitor;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.world.BlockView;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;

/**
 * Hears about every sample a {@link DamageMonitor} takes, for keeping more than
 * the monitor itself does: the vector recorder saves each one as a test case.
 *
 * <p>{@link #begin} runs the moment the explosion is reported, while the target
 * and the blocks around it are as the explosion found them; whatever it returns
 * comes back to {@link #finish} or {@link #discard} once the sample settles.
 *
 * @param <E> the game's type for what can be hurt
 */
public interface SampleRecorder<E> {

    /**
     * An explosion reached {@code target}. Capture what you need now.
     *
     * @return anything; it is handed back when the sample settles
     */
    Object begin(Vec3 origin, Explosive explosive, Tracked<? extends E> target, DamageEstimate predicted);

    /**
     * {@link #begin(Vec3, Explosive, Tracked, DamageEstimate)}, with the blocks as
     * the explosion found them: without the bed that just went off, say. Unless
     * overridden, the blocks are ignored.
     */
    default Object begin(Vec3 origin, Explosive explosive, Tracked<? extends E> target, DamageEstimate predicted,
                         BlockView asFired) {
        return begin(origin, explosive, target, predicted);
    }

    /** The sample settled and was kept. {@code observed} is a lower bound when {@code popped}. */
    void finish(Object token, DamageEstimate predicted, double before, double observed, boolean popped);

    /** The sample was thrown away, or replaced by a stronger explosion in the same tick. */
    default void discard(Object token) {
    }
}
