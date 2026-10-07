package dev.px.core.movement.prediction;

import dev.px.core.entity.Tracked;
import dev.px.core.util.Validate;

/**
 * Where an entity will be some ticks from now: when an explosion lands, when an
 * arrow arrives, whenever something you start now reaches it.
 *
 * <pre>{@code
 * Lookahead.none()                                   // where it is now
 * Lookahead.predicted(Core.prediction())            // where Core's prediction says it will be, when it is sure
 * Lookahead.extrapolated()                          // a straight line along its last move
 * (entity, ticks) -> myOwnGuess(entity, ticks)       // anything else
 * }</pre>
 *
 * <p>Whatever asks never depends on how it is answered: give it
 * {@code Core.prediction()}, your own model, or nothing. The libraries on Core
 * take one wherever timing matters &mdash; the combat searches for targets,
 * you and anyone protected, the projectile aim solver for whoever it aims at.
 *
 * @param <E> the game's type for the entities it is asked about
 */
@FunctionalInterface
public interface Lookahead<E> {

    /**
     * @param entity the entity as it is now
     * @param ticks  how many ticks from now; never negative
     * @return the entity where it will be then: itself for now, or a
     *         {@link Tracked#projected} stand-in somewhere else
     */
    Tracked<? extends E> at(Tracked<? extends E> entity, int ticks);

    /** @return where everyone is now: no looking ahead */
    static <E> Lookahead<E> none() {
        return (entity, ticks) -> entity;
    }

    /** @return a straight line along each entity's last tick of movement: no walls, no gravity */
    static <E> Lookahead<E> extrapolated() {
        return (entity, ticks) -> ticks <= 0 ? entity : entity.projected(entity.extrapolate(ticks));
    }

    /**
     * @return where {@code prediction}'s likeliest future puts each entity; where
     *         it is now while the prediction is not {@linkplain Prediction#isReliable
     *         reliable}, since a guess it does not trust is no better than now
     */
    static <E> Lookahead<E> predicted(PredictionService prediction) {
        return predicted(prediction, false);
    }

    /**
     * @param evenUnreliable whether to use the likeliest future even when the
     *        prediction says it is unreliable
     */
    static <E> Lookahead<E> predicted(PredictionService prediction, boolean evenUnreliable) {
        Validate.notNull(prediction, "prediction");
        return (entity, ticks) -> {
            if (ticks <= 0) {
                return entity;
            }
            Prediction next = prediction.predict(entity, ticks);
            return evenUnreliable || next.isReliable() ? entity.projected(next.positionAt(ticks)) : entity;
        };
    }
}
