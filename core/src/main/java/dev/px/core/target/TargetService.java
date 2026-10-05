package dev.px.core.target;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;
import dev.px.core.service.Service;
import dev.px.core.util.Validate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs {@link TargetSelector}s against the trackers {@code Core.entities()} feeds.
 *
 * <pre>{@code
 * Tracked<EntityPlayer> target = Core.targets().best(enemies);              // from the player's eyes
 * List<Tracked<EntityEnderCrystal>> near = Core.targets().all(crystals, 3); // three best, in order
 * Tracked<EntityEnderCrystal> pop = Core.targets().best(crystals, enemy.getPosition());
 * int threats = Core.targets().count(enemies);
 * }</pre>
 *
 * <h2>Cost</h2>
 *
 * <p>With c candidates the tracker's range query visits and m of them passing
 * every filter:
 *
 * <pre>
 * best, count, any, forEach    O(c)             one pass, no sort, no allocation
 * all                          O(c + m log m)   the sort, over candidates reused between calls
 * accepts                      O(1)
 * </pre>
 *
 * <p>Every query reads the world as of the start of the tick, so ten modules
 * asking in one tick see the same one and pay only for their own filters.
 *
 * <p>A selector whose tracker is named by class and not registered finds
 * nothing. Queries nest: a predicate or sort may run another query, and each gets
 * its own working state. Game thread only.
 */
public final class TargetService implements Service {

    private static final Comparator<Candidate> RANKING = (left, right) -> {
        int byScore = Double.compare(left.score, right.score);
        return byScore != 0 ? byScore : Double.compare(left.distance, right.distance);
    };

    private final EntityService entities;
    private final Deque<Query> idle = new ArrayDeque<>();

    public TargetService(EntityService entities) {
        this.entities = Validate.notNull(entities, "entities");
    }

    @Override
    public String getName() {
        return "Targets";
    }

    @Override
    public void start() {
    }

    // ------------------------------------------------------------- queries

    /** @return the best target as seen from the local player's eyes, or null if none qualifies */
    public <E> Tracked<E> best(TargetSelector<E> selector) {
        Query query = open(selector, null);
        return query == null ? null : query.<E>best();
    }

    /** @return the best target as seen from {@code origin}; the view is still the local player's */
    public <E> Tracked<E> best(TargetSelector<E> selector, Vec3 origin) {
        Query query = open(selector, Validate.notNull(origin, "origin"));
        return query == null ? null : query.<E>best();
    }

    /** @return every target, best first */
    public <E> List<Tracked<E>> all(TargetSelector<E> selector) {
        return all(selector, Integer.MAX_VALUE);
    }

    /** @return up to {@code limit} targets, best first */
    public <E> List<Tracked<E>> all(TargetSelector<E> selector, int limit) {
        Query query = open(selector, null);
        return query == null ? Collections.<Tracked<E>>emptyList() : query.<E>all(limit);
    }

    public <E> List<Tracked<E>> all(TargetSelector<E> selector, Vec3 origin, int limit) {
        Query query = open(selector, Validate.notNull(origin, "origin"));
        return query == null ? Collections.<Tracked<E>>emptyList() : query.<E>all(limit);
    }

    public int count(TargetSelector<?> selector) {
        Query query = open(selector, null);
        return query == null ? 0 : query.count();
    }

    public int count(TargetSelector<?> selector, Vec3 origin) {
        Query query = open(selector, Validate.notNull(origin, "origin"));
        return query == null ? 0 : query.count();
    }

    public boolean any(TargetSelector<?> selector) {
        return best(selector) != null;
    }

    /** Visits every target, unsorted. Allocates nothing. */
    public <E> void forEach(TargetSelector<E> selector, Consumer<? super Tracked<E>> action) {
        Validate.notNull(action, "action");
        Query query = open(selector, null);
        if (query != null) {
            query.forEach(action);
        }
    }

    /**
     * @return whether one entity passes the selector right now, from the local
     *         player's eyes: still held by the selector's tracker, and through
     *         every filter. O(1): how a {@link TargetLock} checks that the target
     *         it holds is still valid without searching again
     */
    public <E> boolean accepts(TargetSelector<E> selector, Tracked<E> entity) {
        if (entity == null || !entity.isTracked()) {
            return false;
        }
        Query query = open(selector, null);
        return query != null && query.check(entity);
    }

    /** @return a lock that keeps a target across ticks until it stops qualifying */
    public <E> TargetLock<E> lock(TargetSelector<E> selector) {
        return new TargetLock<>(this, selector);
    }

    // ------------------------------------------------------------ internals

    /**
     * @return a query ready to run, or null when the selector's tracker is not
     *         registered, or there is no local player and no origin to look from
     */
    private Query open(TargetSelector<?> selector, Vec3 origin) {
        Validate.notNull(selector, "selector");
        EntityTracker<?> tracker = selector.resolve(entities);
        if (tracker == null) {
            return null;
        }
        Tracked<?> self = entities.getSelf();
        double x;
        double y;
        double z;
        if (origin != null) {
            x = origin.getX();
            y = origin.getY();
            z = origin.getZ();
        } else if (self != null) {
            x = self.getX();
            y = self.getEyeY();
            z = self.getZ();
        } else {
            return null;
        }
        Query query = idle.isEmpty() ? new Query() : idle.pop();
        query.begin(selector, tracker, self, x, y, z);
        return query;
    }

    private void close(Query query) {
        query.end();
        idle.push(query);
    }

    /**
     * One run of one selector: its filters' inputs, read once, and whatever it is
     * collecting.
     *
     * <p>Raw over the entity type, because one pooled query serves selectors of
     * every type; the public methods above are what keep the types honest.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private final class Query implements Consumer<Tracked> {

        private final TargetContext context = new TargetContext();
        private TargetSelector selector;
        private EntityTracker tracker;
        private double range;
        private double squaredRange;
        private double fovCosine;

        private Mode mode;
        private Tracked best;
        private double bestScore;
        private double bestDistance;
        private int count;
        private Consumer action;
        private Candidate[] candidates = new Candidate[16];

        void begin(TargetSelector<?> selector, EntityTracker<?> tracker, Tracked<?> self, double x, double y, double z) {
            this.selector = selector;
            this.tracker = tracker;
            this.range = selector.currentRange();
            this.squaredRange = Double.isInfinite(range) ? Double.POSITIVE_INFINITY : range * range;
            this.fovCosine = selector.currentFovCosine();
            context.begin(self, x, y, z);
        }

        void end() {
            context.end();
            selector = null;
            tracker = null;
            best = null;
            action = null;
            for (int i = 0; i < count && i < candidates.length; i++) {
                if (candidates[i] != null) {
                    candidates[i].entity = null;
                }
            }
            count = 0;
        }

        <E> Tracked<E> best() {
            try {
                mode = Mode.BEST;
                best = null;
                bestScore = Double.POSITIVE_INFINITY;
                bestDistance = Double.POSITIVE_INFINITY;
                search();
                return best;
            } finally {
                close(this);
            }
        }

        <E> List<Tracked<E>> all(int limit) {
            try {
                mode = Mode.COLLECT;
                count = 0;
                search();
                Arrays.sort(candidates, 0, count, RANKING);
                int size = Math.min(count, Math.max(0, limit));
                List<Tracked<E>> result = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    result.add(candidates[i].entity);
                }
                return result;
            } finally {
                close(this);
            }
        }

        int count() {
            try {
                mode = Mode.COUNT;
                count = 0;
                search();
                return count;
            } finally {
                count = 0;
                close(this);
            }
        }

        void forEach(Consumer visit) {
            try {
                mode = Mode.VISIT;
                action = visit;
                search();
            } finally {
                close(this);
            }
        }

        boolean check(Tracked entity) {
            try {
                return tracker.contains(entity) && selector.accepts(entity, context, squaredRange, fovCosine);
            } finally {
                close(this);
            }
        }

        private void search() {
            tracker.forEachWithin(context.getOriginX(), context.getOriginY(), context.getOriginZ(), range, this);
        }

        @Override
        public void accept(Tracked entity) {
            if (!selector.accepts(entity, context, squaredRange, fovCosine)) {
                return;
            }
            switch (mode) {
                case BEST: {
                    double score = selector.getSort().score(entity, context);
                    double distance = context.squaredDistanceTo(entity);
                    if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                        best = entity;
                        bestScore = score;
                        bestDistance = distance;
                    }
                    break;
                }
                case COLLECT: {
                    if (count == candidates.length) {
                        candidates = Arrays.copyOf(candidates, count * 2);
                    }
                    Candidate candidate = candidates[count];
                    if (candidate == null) {
                        candidate = new Candidate();
                        candidates[count] = candidate;
                    }
                    candidate.entity = entity;
                    candidate.score = selector.getSort().score(entity, context);
                    candidate.distance = context.squaredDistanceTo(entity);
                    count++;
                    break;
                }
                case COUNT:
                    count++;
                    break;
                case VISIT:
                    action.accept(entity);
                    break;
                default:
                    break;
            }
        }
    }

    private enum Mode { BEST, COLLECT, COUNT, VISIT }

    /** A target and its ranking, pooled per query so sorting builds nothing new after warm-up. */
    @SuppressWarnings("rawtypes")
    private static final class Candidate {
        private Tracked entity;
        private double score;
        private double distance;
    }
}
