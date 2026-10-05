package dev.px.core.target;

import dev.px.core.entity.Tracked;
import dev.px.core.util.Validate;

import java.util.function.ToDoubleFunction;

/**
 * How candidates are ranked: a score per entity, lowest first.
 *
 * <p>A score, not a comparator, so it is computed once per candidate rather than
 * once per comparison, and choosing the best of m candidates is one pass rather
 * than a sort. Ties are broken by distance to the box.
 *
 * <p>Core ships only the rankings that mean the same thing in any world:
 * distance, angle from the view, and how long an entity has been tracked.
 * Anything about the game is read straight off the game's object:
 *
 * <pre>{@code
 * TargetSort.DISTANCE                                  // nearest first, the default
 * TargetSort.by(EntityPlayer::getHealth)               // lowest health first
 * TargetSort.by(EntityPlayer::getHealth).reversed()    // highest first
 * TargetSort.<EntityPlayer>by(p -> p.getTotalArmorValue() * 2 + p.getHealth())
 * (entity, context) -> damageFrom(context.getSelf(), entity.get())   // anything else
 * }</pre>
 *
 * <p>A score of {@link Double#POSITIVE_INFINITY} means "rank last", and stays last
 * when the sort is {@link #reversed()}; that is what a key returning
 * {@code NaN} scores.
 *
 * @param <E> the game's type the sort reads; {@code Object} for one that reads none
 */
@FunctionalInterface
public interface TargetSort<E> {

    /** Nearest box to the query's origin. The default. */
    TargetSort<Object> DISTANCE = (entity, context) -> context.squaredDistanceTo(entity);

    /** Least turn from where the local player looks to the centre of the box. */
    TargetSort<Object> ANGLE = (entity, context) -> context.angleTo(entity);

    /** Tracked for the fewest ticks: whatever just appeared. */
    TargetSort<Object> NEWEST = (entity, context) -> entity.getTicksTracked();

    /** Tracked for the most ticks. */
    TargetSort<Object> OLDEST = (entity, context) -> -entity.getTicksTracked();

    /**
     * @return lower is better; {@link Double#POSITIVE_INFINITY} to rank last. Called
     *         once per candidate that passed every filter, on the game thread
     */
    double score(Tracked<? extends E> entity, TargetContext context);

    /** @return the same ranking, highest first, with unscorable entities still last */
    default TargetSort<E> reversed() {
        TargetSort<E> self = this;
        return (entity, context) -> {
            double score = self.score(entity, context);
            return score == Double.POSITIVE_INFINITY ? score : -score;
        };
    }

    /** @return lowest value of a number read off the game's object first; NaN ranks last */
    static <E> TargetSort<E> by(ToDoubleFunction<? super E> key) {
        Validate.notNull(key, "key");
        return (entity, context) -> {
            double value = key.applyAsDouble(entity.get());
            return Double.isNaN(value) ? Double.POSITIVE_INFINITY : value;
        };
    }
}
