package dev.px.core.target;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.TrackedEntity;
import dev.px.core.math.Vec3;
import dev.px.core.service.Service;
import dev.px.core.social.SocialService;
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
 * Runs {@link TargetSelector}s against the world {@code Core.entities()} holds.
 *
 * <pre>{@code
 * TrackedEntity target = Core.targets().best(enemies);             // from the player's eyes
 * List<TrackedEntity> near = Core.targets().all(crystals, 3);      // three best, in order
 * TrackedEntity pop = Core.targets().best(crystals, enemy.getPosition());   // from another point
 * int threats = Core.targets().count(enemies);
 * }</pre>
 *
 * <h2>Cost</h2>
 *
 * <p>With c candidates the range query visits (see {@code EntityService}) and m
 * of them passing every filter:
 *
 * <pre>
 * best, count, any, forEach    O(c)             one pass, no sort, no allocation
 * all                          O(c + m log m)   the sort, over candidates reused between calls
 * accepts                      O(1)
 * </pre>
 *
 * <p>Every query reads the snapshot from the start of the tick, so ten modules
 * asking in one tick see the same world and pay only for their own filters.
 *
 * <p>Queries nest: a predicate or sort may run another query, and each gets its
 * own working state. Game thread only.
 */
public final class TargetService implements Service {

    private static final Comparator<Candidate> RANKING = (left, right) -> {
        int byScore = Double.compare(left.score, right.score);
        return byScore != 0 ? byScore : Double.compare(left.distance, right.distance);
    };

    private final EntityService entities;
    private final SocialService social;
    private final Deque<Query> idle = new ArrayDeque<>();

    /** @param social consulted for friends; null to treat nobody as a friend */
    public TargetService(EntityService entities, SocialService social) {
        this.entities = Validate.notNull(entities, "entities");
        this.social = social;
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
    public TrackedEntity best(TargetSelector selector) {
        Query query = open(selector, null);
        return query == null ? null : query.best();
    }

    /** @return the best target as seen from {@code origin}; the view is still the local player's */
    public TrackedEntity best(TargetSelector selector, Vec3 origin) {
        Query query = open(selector, Validate.notNull(origin, "origin"));
        return query == null ? null : query.best();
    }

    /** @return every target, best first */
    public List<TrackedEntity> all(TargetSelector selector) {
        return all(selector, Integer.MAX_VALUE);
    }

    /** @return up to {@code limit} targets, best first */
    public List<TrackedEntity> all(TargetSelector selector, int limit) {
        Query query = open(selector, null);
        return query == null ? Collections.<TrackedEntity>emptyList() : query.all(limit);
    }

    public List<TrackedEntity> all(TargetSelector selector, Vec3 origin, int limit) {
        Query query = open(selector, Validate.notNull(origin, "origin"));
        return query == null ? Collections.<TrackedEntity>emptyList() : query.all(limit);
    }

    public int count(TargetSelector selector) {
        Query query = open(selector, null);
        return query == null ? 0 : query.count();
    }

    public int count(TargetSelector selector, Vec3 origin) {
        Query query = open(selector, Validate.notNull(origin, "origin"));
        return query == null ? 0 : query.count();
    }

    public boolean any(TargetSelector selector) {
        return best(selector) != null;
    }

    /** Visits every target, unsorted. Allocates nothing. */
    public void forEach(TargetSelector selector, Consumer<? super TrackedEntity> action) {
        Validate.notNull(action, "action");
        Query query = open(selector, null);
        if (query != null) {
            query.forEach(action);
        }
    }

    /**
     * @return whether one entity passes the selector right now, from the local
     *         player's eyes. O(1): how a {@link TargetLock} checks that the target
     *         it holds is still valid without searching again
     */
    public boolean accepts(TargetSelector selector, TrackedEntity entity) {
        if (entity == null || !entity.isTracked()) {
            return false;
        }
        Query query = open(selector, null);
        return query != null && query.check(entity);
    }

    /** @return a lock that keeps a target across ticks until it stops qualifying */
    public TargetLock lock(TargetSelector selector) {
        return new TargetLock(this, selector);
    }

    // ------------------------------------------------------------ internals

    /** @return a query ready to run, or null when there is no local player and no origin to look from */
    private Query open(TargetSelector selector, Vec3 origin) {
        Validate.notNull(selector, "selector");
        TrackedEntity self = entities.getSelf();
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
        query.begin(selector, self, x, y, z);
        return query;
    }

    private void close(Query query) {
        query.end();
        idle.push(query);
    }

    /** One run of one selector: its filters' inputs, read once, and whatever it is collecting. */
    private final class Query implements Consumer<TrackedEntity> {

        private final TargetContext context = new TargetContext();
        private TargetSelector selector;
        private double range;
        private double squaredRange;
        private double fovCosine;

        private Mode mode;
        private TrackedEntity best;
        private double bestScore;
        private double bestDistance;
        private int count;
        private Consumer<? super TrackedEntity> action;
        private Candidate[] candidates = new Candidate[16];

        void begin(TargetSelector selector, TrackedEntity self, double x, double y, double z) {
            this.selector = selector;
            this.range = selector.currentRange();
            this.squaredRange = Double.isInfinite(range) ? Double.POSITIVE_INFINITY : range * range;
            this.fovCosine = selector.currentFovCosine();
            context.begin(social, self, x, y, z);
        }

        void end() {
            context.end();
            selector = null;
            best = null;
            action = null;
            for (int i = 0; i < count && i < candidates.length; i++) {
                if (candidates[i] != null) {
                    candidates[i].entity = null;
                }
            }
            count = 0;
        }

        TrackedEntity best() {
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

        List<TrackedEntity> all(int limit) {
            try {
                mode = Mode.COLLECT;
                count = 0;
                search();
                Arrays.sort(candidates, 0, count, RANKING);
                int size = Math.min(count, Math.max(0, limit));
                List<TrackedEntity> result = new ArrayList<>(size);
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

        void forEach(Consumer<? super TrackedEntity> visit) {
            try {
                mode = Mode.VISIT;
                action = visit;
                search();
            } finally {
                close(this);
            }
        }

        boolean check(TrackedEntity entity) {
            try {
                return selector.accepts(entity, context, squaredRange, fovCosine);
            } finally {
                close(this);
            }
        }

        private void search() {
            entities.forEachWithin(selector.getCategories(), context.getOriginX(), context.getOriginY(),
                    context.getOriginZ(), range, this);
        }

        @Override
        public void accept(TrackedEntity entity) {
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
    private static final class Candidate {
        private TrackedEntity entity;
        private double score;
        private double distance;
    }
}
