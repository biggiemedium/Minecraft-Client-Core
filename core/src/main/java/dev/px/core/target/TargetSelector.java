package dev.px.core.target;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.util.Validate;

import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/**
 * Which of a tracker's entities count as targets, and which of them is best.
 * Built once, kept in a field, and run as often as needed.
 *
 * <pre>{@code
 * private final TargetSelector<EntityPlayer> enemies = TargetSelector.from(EnemyTracker.class)
 *         .range(reach::getDouble)                 // your setting, read on every query
 *         .fov(fov::getDouble)
 *         .where(p -> p.hurtTime == 0)             // the game's object, already typed
 *         .sort(TargetSort.by(EntityPlayer::getHealth))
 *         .build();
 *
 * private final TargetSelector<EntityEnderCrystal> crystals = TargetSelector.from(CrystalTracker.class)
 *         .range(breakRange::getDouble)
 *         .minTicksTracked(1)                      // not the one that appeared this tick
 *         .build();
 *
 * Tracked<EntityPlayer> target = Core.targets().best(enemies);
 * }</pre>
 *
 * <p>{@link #from(Class)} names the tracker by class and finds it on each query,
 * so a module can build its selectors in field initialisers before the tracker
 * is registered. {@link #from(EntityTracker)} takes one directly.
 *
 * <h2>Nothing is assumed</h2>
 *
 * <p>A selector matches exactly what you tell it to. By default that is every
 * entity in its tracker, at any range and any angle, nearest first. What counts
 * as dead, a teammate or a friend is a question about your game, so it is your
 * tracker's {@code accepts} or your {@code where}.
 *
 * <h2>Cost</h2>
 *
 * <p>Filters run cheapest first:
 *
 * <ol>
 *   <li>ticks tracked
 *   <li>range, exactly, to the box &mdash; and the tracker's grid means far
 *       entities are never visited at all
 *   <li>field of view &mdash; a dot product against {@code cos(fov / 2)}, no
 *       inverse trigonometry
 *   <li>your {@code where} filters, last, since they can cost anything
 * </ol>
 *
 * <p>Immutable and safe to share between modules.
 *
 * @param <E> the game's type for what the tracker holds
 */
public final class TargetSelector<E> {

    private final EntityTracker<E> tracker;
    private final Class<? extends EntityTracker<E>> trackerType;
    private final DoubleSupplier range;
    private final DoubleSupplier fov;
    private final int minTicksTracked;
    private final int maxTicksTracked;
    private final Predicate<? super E> where;
    private final Predicate<? super Tracked<E>> whereTracked;
    private final TargetSort<? super E> sort;

    private TargetSelector(Builder<E> builder) {
        this.tracker = builder.tracker;
        this.trackerType = builder.trackerType;
        this.range = builder.range;
        this.fov = builder.fov;
        this.minTicksTracked = builder.minTicksTracked;
        this.maxTicksTracked = builder.maxTicksTracked;
        this.where = builder.where;
        this.whereTracked = builder.whereTracked;
        this.sort = builder.sort;
    }

    /** Targets from the registered tracker of this class, looked up on each query. */
    public static <E> Builder<E> from(Class<? extends EntityTracker<E>> trackerType) {
        return new Builder<>(null, Validate.notNull(trackerType, "trackerType"));
    }

    /** Targets from this tracker. */
    public static <E> Builder<E> from(EntityTracker<E> tracker) {
        return new Builder<>(Validate.notNull(tracker, "tracker"), null);
    }

    /** @return a builder starting from this selector's settings, for a variant of it */
    public Builder<E> toBuilder() {
        Builder<E> builder = new Builder<>(tracker, trackerType);
        builder.range = range;
        builder.fov = fov;
        builder.minTicksTracked = minTicksTracked;
        builder.maxTicksTracked = maxTicksTracked;
        builder.where = where;
        builder.whereTracked = whereTracked;
        builder.sort = sort;
        return builder;
    }

    /** @return the tracker this selector reads, or null when it names one by class and none is registered */
    public EntityTracker<E> resolve(EntityService entities) {
        if (tracker != null) {
            return tracker;
        }
        return entities.get(trackerType);
    }

    /** @return the range right now, or {@link Double#POSITIVE_INFINITY} for none */
    public double currentRange() {
        if (range == null) {
            return Double.POSITIVE_INFINITY;
        }
        double value = range.getAsDouble();
        return value >= 0d ? value : 0d;
    }

    public TargetSort<? super E> getSort() {
        return sort;
    }

    /**
     * @param squaredRange the square of {@link #currentRange()}, read once per query
     * @param fovCosine {@code cos(fov / 2)}, or -1 or below for no limit
     * @return whether the entity passes every filter
     */
    boolean accepts(Tracked<E> entity, TargetContext context, double squaredRange, double fovCosine) {
        int ticks = entity.getTicksTracked();
        if (ticks < minTicksTracked || ticks > maxTicksTracked) {
            return false;
        }
        if (squaredRange != Double.POSITIVE_INFINITY && context.squaredDistanceTo(entity) > squaredRange) {
            return false;
        }
        if (fovCosine > -1d && context.cosineTo(entity) < fovCosine) {
            return false;
        }
        if (where != null && !where.test(entity.get())) {
            return false;
        }
        return whereTracked == null || whereTracked.test(entity);
    }

    /** @return {@code cos(fov / 2)} for the field of view right now, or -1 when there is none */
    double currentFovCosine() {
        if (fov == null) {
            return -1d;
        }
        double degrees = fov.getAsDouble();
        if (degrees >= 360d) {
            return -1d;
        }
        return Math.cos(Math.toRadians(Math.max(0d, degrees) / 2d));
    }

    public static final class Builder<E> {

        private final EntityTracker<E> tracker;
        private final Class<? extends EntityTracker<E>> trackerType;
        private DoubleSupplier range;
        private DoubleSupplier fov;
        private int minTicksTracked = 0;
        private int maxTicksTracked = Integer.MAX_VALUE;
        private Predicate<? super E> where;
        private Predicate<? super Tracked<E>> whereTracked;
        private TargetSort<? super E> sort = TargetSort.DISTANCE;

        private Builder(EntityTracker<E> tracker, Class<? extends EntityTracker<E>> trackerType) {
            this.tracker = tracker;
            this.trackerType = trackerType;
        }

        /** Box within {@code range} of the origin. */
        public Builder<E> range(double range) {
            Validate.check(range >= 0d, "range must not be negative");
            this.range = () -> range;
            return this;
        }

        /** Box within a range read on every query, such as your setting. */
        public Builder<E> range(DoubleSupplier range) {
            this.range = Validate.notNull(range, "range");
            return this;
        }

        /** Centre of the box within a cone of {@code degrees} around the local player's view. 360 or more is no limit. */
        public Builder<E> fov(double degrees) {
            this.fov = () -> degrees;
            return this;
        }

        public Builder<E> fov(DoubleSupplier degrees) {
            this.fov = Validate.notNull(degrees, "degrees");
            return this;
        }

        /** Only entities tracked for at least this many ticks in a row. */
        public Builder<E> minTicksTracked(int ticks) {
            this.minTicksTracked = ticks;
            return this;
        }

        /** Only entities tracked for at most this many ticks in a row. */
        public Builder<E> maxTicksTracked(int ticks) {
            this.maxTicksTracked = ticks;
            return this;
        }

        /** Adds a filter on the game's object. Several are all required. They run after every built-in filter. */
        public Builder<E> where(Predicate<? super E> predicate) {
            Validate.notNull(predicate, "predicate");
            Predicate<? super E> previous = this.where;
            this.where = previous == null ? predicate : entity -> previous.test(entity) && predicate.test(entity);
            return this;
        }

        /** Adds a filter on what Core measured &mdash; velocity, box, ticks tracked. Runs after {@link #where}. */
        public Builder<E> whereTracked(Predicate<? super Tracked<E>> predicate) {
            Validate.notNull(predicate, "predicate");
            Predicate<? super Tracked<E>> previous = this.whereTracked;
            this.whereTracked = previous == null ? predicate
                    : entity -> previous.test(entity) && predicate.test(entity);
            return this;
        }

        public Builder<E> sort(TargetSort<? super E> sort) {
            this.sort = Validate.notNull(sort, "sort");
            return this;
        }

        public TargetSelector<E> build() {
            Validate.check(minTicksTracked <= maxTicksTracked, "minTicksTracked must not exceed maxTicksTracked");
            return new TargetSelector<>(this);
        }
    }
}
