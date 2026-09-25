package dev.px.core.target;

import dev.px.core.entity.EntityAttribute;
import dev.px.core.entity.TrackedEntity;
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
 * distance, angle from the view, and how long an entity has been seen. Anything
 * about the game &mdash; health, armor, damage &mdash; is one of your
 * {@link EntityAttribute}s:
 *
 * <pre>{@code
 * TargetSort.DISTANCE                                  // nearest first, the default
 * TargetSort.by(Stats.HEALTH)                          // lowest of your attribute first
 * TargetSort.by(Stats.HEALTH).reversed()               // highest first
 * TargetSort.by(e -> e.get(Stats.ARMOR) * 2 + e.get(Stats.HEALTH))
 * (e, context) -> damageFrom(context.getSelf(), e)     // anything else: the interface itself
 * }</pre>
 *
 * <p>A score of {@link Double#POSITIVE_INFINITY} means "rank last", and stays last
 * when the sort is {@link #reversed()}; that is what an entity missing the
 * attribute being sorted on scores.
 */
@FunctionalInterface
public interface TargetSort {

    /** Nearest box to the query's origin. The default. */
    TargetSort DISTANCE = (entity, context) -> context.squaredDistanceTo(entity);

    /** Least turn from where the local player looks to the centre of the box. */
    TargetSort ANGLE = (entity, context) -> context.angleTo(entity);

    /** Seen for the fewest ticks: whatever just appeared. */
    TargetSort NEWEST = (entity, context) -> entity.getTicksTracked();

    /** Seen for the most ticks. */
    TargetSort OLDEST = (entity, context) -> -entity.getTicksTracked();

    /**
     * @return lower is better; {@link Double#POSITIVE_INFINITY} to rank last. Called
     *         once per candidate that passed every filter, on the game thread
     */
    double score(TrackedEntity entity, TargetContext context);

    /** @return the same ranking, highest first, with unscorable entities still last */
    default TargetSort reversed() {
        TargetSort self = this;
        return (entity, context) -> {
            double score = self.score(entity, context);
            return score == Double.POSITIVE_INFINITY ? score : -score;
        };
    }

    /** @return lowest value of your attribute first; entities without it last */
    static TargetSort by(EntityAttribute attribute) {
        Validate.notNull(attribute, "attribute");
        return (entity, context) -> {
            double value = entity.get(attribute);
            return Double.isNaN(value) ? Double.POSITIVE_INFINITY : value;
        };
    }

    /** @return lowest value of any number you compute from the entity first; NaN ranks last */
    static TargetSort by(ToDoubleFunction<TrackedEntity> key) {
        Validate.notNull(key, "key");
        return (entity, context) -> {
            double value = key.applyAsDouble(entity);
            return Double.isNaN(value) ? Double.POSITIVE_INFINITY : value;
        };
    }
}
