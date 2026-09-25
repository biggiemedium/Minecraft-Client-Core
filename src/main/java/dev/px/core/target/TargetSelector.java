package dev.px.core.target;

import dev.px.core.entity.EntityAttribute;
import dev.px.core.entity.EntityCategory;
import dev.px.core.entity.EntityTag;
import dev.px.core.entity.TagSet;
import dev.px.core.entity.TrackedEntity;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.DoublePredicate;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/**
 * Which entities count as targets, and which of them is best, in your own
 * categories, tags and attributes. Built once, kept in a field, and run as often
 * as needed.
 *
 * <pre>{@code
 * private final TargetSelector enemies = TargetSelector.builder()
 *         .categories(Kinds.PLAYER, Kinds.MONSTER)
 *         .without(Tags.DEAD, Tags.TEAMMATE)
 *         .excludeFriends()
 *         .range(reach::getDouble)                 // your setting, read on every query
 *         .sort(TargetSort.by(Stats.HEALTH))
 *         .build();
 *
 * private final TargetSelector crystals = TargetSelector.builder()
 *         .categories(Kinds.CRYSTAL)
 *         .range(breakRange::getDouble)
 *         .minTicksTracked(1)                      // not the one that appeared this tick
 *         .build();
 *
 * TrackedEntity target = Core.targets().best(enemies);
 * }</pre>
 *
 * <h2>Nothing is assumed</h2>
 *
 * <p>A selector matches exactly what you tell it to. By default that is every
 * category, at any range and any angle, nearest first. The only entity ever left
 * out without being asked is the local player, since a query looks from there.
 * Core has no idea which of your tags mean "dead" or "on my team", so it never
 * excludes them for you.
 *
 * <h2>Cost</h2>
 *
 * <p>Filters run cheapest first:
 *
 * <ol>
 *   <li>category &mdash; the range query only visits the categories selected
 *   <li>tags &mdash; one AND per 64 tags declared, however many are listed
 *   <li>ticks tracked
 *   <li>range, exactly, to the box
 *   <li>friends &mdash; a map lookup, and only for entities with a name
 *   <li>field of view &mdash; a dot product against {@code cos(fov / 2)}, no
 *       inverse trigonometry
 *   <li>your {@code where} filters, last, since they can cost anything
 * </ol>
 *
 * <p>Immutable and safe to share between modules.
 */
public final class TargetSelector {

    private final Set<EntityCategory> categories;
    private final TagSet withAny;
    private final TagSet withAll;
    private final TagSet without;
    private final boolean excludeFriends;
    private final DoubleSupplier range;
    private final DoubleSupplier fov;
    private final int minTicksTracked;
    private final int maxTicksTracked;
    private final Predicate<? super TrackedEntity> where;
    private final TargetSort sort;

    private TargetSelector(Builder builder) {
        this.categories = builder.categories == null ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(builder.categories));
        this.withAny = TagSet.of(builder.withAny);
        this.withAll = TagSet.of(builder.withAll);
        this.without = TagSet.of(builder.without);
        this.excludeFriends = builder.excludeFriends;
        this.range = builder.range;
        this.fov = builder.fov;
        this.minTicksTracked = builder.minTicksTracked;
        this.maxTicksTracked = builder.maxTicksTracked;
        this.where = builder.where;
        this.sort = builder.sort;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder starting from this selector's settings, for a variant of it */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.categories = categories == null ? null : new LinkedHashSet<>(categories);
        builder.withAny.addAll(withAny.getTags());
        builder.withAll.addAll(withAll.getTags());
        builder.without.addAll(without.getTags());
        builder.excludeFriends = excludeFriends;
        builder.range = range;
        builder.fov = fov;
        builder.minTicksTracked = minTicksTracked;
        builder.maxTicksTracked = maxTicksTracked;
        builder.where = where;
        builder.sort = sort;
        return builder;
    }

    /** @return the selected categories, or null for every category */
    public Set<EntityCategory> getCategories() {
        return categories;
    }

    /** @return the range right now, or {@link Double#POSITIVE_INFINITY} for none */
    public double currentRange() {
        if (range == null) {
            return Double.POSITIVE_INFINITY;
        }
        double value = range.getAsDouble();
        return value >= 0d ? value : 0d;
    }

    public TargetSort getSort() {
        return sort;
    }

    /**
     * @param squaredRange the square of {@link #currentRange()}, read once per query
     * @param fovCosine {@code cos(fov / 2)}, or -1 or below for no limit
     * @return whether the entity passes every filter
     */
    boolean accepts(TrackedEntity entity, TargetContext context, double squaredRange, double fovCosine) {
        if (entity.isSelf()) {
            return false;
        }
        if (categories != null && !categories.contains(entity.getCategory())) {
            return false;
        }
        if (!without.isEmpty() && without.matchesAny(entity)) {
            return false;
        }
        if (!withAll.matchesAll(entity)) {
            return false;
        }
        if (!withAny.isEmpty() && !withAny.matchesAny(entity)) {
            return false;
        }
        int ticks = entity.getTicksTracked();
        if (ticks < minTicksTracked || ticks > maxTicksTracked) {
            return false;
        }
        if (squaredRange != Double.POSITIVE_INFINITY && context.squaredDistanceTo(entity) > squaredRange) {
            return false;
        }
        if (excludeFriends && entity.getName() != null && context.isFriend(entity)) {
            return false;
        }
        if (fovCosine > -1d && context.cosineTo(entity) < fovCosine) {
            return false;
        }
        return where == null || where.test(entity);
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

    public static final class Builder {

        private Set<EntityCategory> categories;
        private final Set<EntityTag> withAny = new LinkedHashSet<>();
        private final Set<EntityTag> withAll = new LinkedHashSet<>();
        private final Set<EntityTag> without = new LinkedHashSet<>();
        private boolean excludeFriends;
        private DoubleSupplier range;
        private DoubleSupplier fov;
        private int minTicksTracked = 0;
        private int maxTicksTracked = Integer.MAX_VALUE;
        private Predicate<? super TrackedEntity> where;
        private TargetSort sort = TargetSort.DISTANCE;

        private Builder() {
        }

        /** Only entities in these categories. Every category when never called. */
        public Builder categories(EntityCategory... categories) {
            Validate.check(categories.length > 0, "at least one category is needed");
            this.categories = new LinkedHashSet<>(Arrays.asList(categories));
            return this;
        }

        /** Only entities with at least one of these tags. */
        public Builder withAny(EntityTag... tags) {
            withAny.addAll(Arrays.asList(tags));
            return this;
        }

        /** Only entities with every one of these tags. */
        public Builder withAll(EntityTag... tags) {
            withAll.addAll(Arrays.asList(tags));
            return this;
        }

        /** No entity with any of these tags. */
        public Builder without(EntityTag... tags) {
            without.addAll(Arrays.asList(tags));
            return this;
        }

        /** Leaves out entities whose name is on the friends list, {@code Core.social()}. */
        public Builder excludeFriends() {
            return excludeFriends(true);
        }

        public Builder excludeFriends(boolean exclude) {
            this.excludeFriends = exclude;
            return this;
        }

        /** Box within {@code range} of the origin. */
        public Builder range(double range) {
            Validate.check(range >= 0d, "range must not be negative");
            this.range = () -> range;
            return this;
        }

        /** Box within a range read on every query, such as your setting. */
        public Builder range(DoubleSupplier range) {
            this.range = Validate.notNull(range, "range");
            return this;
        }

        /** Centre of the box within a cone of {@code degrees} around the local player's view. 360 or more is no limit. */
        public Builder fov(double degrees) {
            this.fov = () -> degrees;
            return this;
        }

        public Builder fov(DoubleSupplier degrees) {
            this.fov = Validate.notNull(degrees, "degrees");
            return this;
        }

        /** Only entities seen for at least this many ticks in a row. */
        public Builder minTicksTracked(int ticks) {
            this.minTicksTracked = ticks;
            return this;
        }

        /** Only entities seen for at most this many ticks in a row. */
        public Builder maxTicksTracked(int ticks) {
            this.maxTicksTracked = ticks;
            return this;
        }

        /** Only entities whose attribute passes {@code test}. An entity without the attribute fails. */
        public Builder where(EntityAttribute attribute, DoublePredicate test) {
            Validate.notNull(attribute, "attribute");
            Validate.notNull(test, "test");
            return where(entity -> {
                double value = entity.get(attribute);
                return !Double.isNaN(value) && test.test(value);
            });
        }

        /** Adds a filter of your own. Several are all required. They run after every built-in filter. */
        public Builder where(Predicate<? super TrackedEntity> predicate) {
            Validate.notNull(predicate, "predicate");
            Predicate<? super TrackedEntity> previous = this.where;
            this.where = previous == null ? predicate : entity -> previous.test(entity) && predicate.test(entity);
            return this;
        }

        public Builder sort(TargetSort sort) {
            this.sort = Validate.notNull(sort, "sort");
            return this;
        }

        public TargetSelector build() {
            Validate.check(minTicksTracked <= maxTicksTracked, "minTicksTracked must not exceed maxTicksTracked");
            return new TargetSelector(this);
        }
    }
}
