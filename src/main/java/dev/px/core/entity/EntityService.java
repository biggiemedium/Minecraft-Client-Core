package dev.px.core.entity;

import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.core.util.spatial.SpatialGrid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every entity in the world, read once a tick through your {@link EntitySource}
 * and indexed so "what is near here" does not ask everything.
 *
 * <pre>{@code
 * Core.entities().setSource(new MyEntities());                    // once, from the adapter
 *
 * TrackedEntity self = Core.entities().getSelf();
 * for (TrackedEntity e : Core.entities().ofCategory(Kinds.CRYSTAL)) { ... }
 * Core.entities().forEachWithin(Kinds.PLAYER, x, y, z, 12, player -> ...);
 * }</pre>
 *
 * <p>This is the world, not the targeting: it has no opinion on who is worth
 * attacking, and no idea what any category means. {@code Core.targets()} is
 * built on it, and so can ESP, radar and nametags be.
 *
 * <h2>Cost</h2>
 *
 * <p>With n entities in the world, a category holding n<sub>c</sub> of them,
 * and a query returning m:
 *
 * <pre>
 * refresh           O(n)          once a tick; nothing allocated for entities already known
 * ofCategory        O(1)          a live view
 * forEachWithin     O(n_c)        for a small category (see {@link #setScanLimit}), a plain scan
 *                   O(b + k)      otherwise, through a spatial grid over that category:
 *                                 b buckets the radius covers, k candidates in them
 * </pre>
 *
 * <p>A category's grid is built on the first range query of the tick that
 * needs it, in O(n<sub>c</sub>), and not at all on a tick with no such query, so
 * a module that looks at one category never pays to index the others.
 *
 * <p>Distances are to the <b>box</b>, not the position. The grid indexes
 * positions, so each category's search radius is widened by the furthest any
 * box reached from its position that tick, and every candidate is then measured
 * exactly.
 *
 * <p>Game thread only.
 */
public final class EntityService implements Service {

    /** A category with at most this many entities is scanned rather than indexed, until {@link #setScanLimit} says otherwise. */
    public static final int DEFAULT_SCAN_LIMIT = 32;

    /** Grid cell edge until {@link #setCellSize} says otherwise. A tuning knob, not a fact about any game. */
    public static final double DEFAULT_CELL_SIZE = 8d;

    private final CoreLogger logger;
    private final EventBus bus;
    private double cellSize;
    private final Listener listener = new Listener();
    private final Writer writer = new Writer();
    private final BoxFilter filter = new BoxFilter();

    private final Map<Object, TrackedEntity> byHandle = new IdentityHashMap<>();
    private final List<TrackedEntity> all = new ArrayList<>();
    private final List<TrackedEntity> allView = Collections.unmodifiableList(all);

    /** Indexed by category slot; grown as categories are first seen. */
    private final List<Bucket> buckets = new ArrayList<>();

    private EntitySource<Object> source;
    private TrackedEntity self;
    private long generation;
    private int scanLimit = DEFAULT_SCAN_LIMIT;
    private boolean autoRefresh = true;
    private boolean warnedAboutSource;
    private boolean warnedAboutRead;

    public EntityService(CoreLogger logger, EventBus bus) {
        this(logger, bus, DEFAULT_CELL_SIZE);
    }

    /** @param cellSize the spatial grid's cell edge; near the radius queried most is best */
    public EntityService(CoreLogger logger, EventBus bus, double cellSize) {
        Validate.check(cellSize > 0d, "cellSize must be positive");
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        this.cellSize = cellSize;
    }

    @Override
    public String getName() {
        return "Entities";
    }

    @Override
    public void start() {
        bus.subscribe(listener);
    }

    @Override
    public void stop() {
        bus.unsubscribe(listener);
        clear();
    }

    // -------------------------------------------------------------- source

    /** @param source your world, or null to stop reading one */
    @SuppressWarnings("unchecked")
    public void setSource(EntitySource<?> source) {
        this.source = (EntitySource<Object>) source;
        this.warnedAboutSource = false;
        this.warnedAboutRead = false;
    }

    public boolean hasSource() {
        return source != null;
    }

    /**
     * Whether the snapshot is refreshed at the start of every tick, ahead of every
     * module. On by default. Turn it off to call {@link #refresh()} yourself at a
     * better point in your version's loop.
     */
    public void setAutoRefresh(boolean autoRefresh) {
        this.autoRefresh = autoRefresh;
    }

    /**
     * @param limit a category with at most this many entities is scanned rather
     *        than indexed; scanning a few is cheaper than building a grid for them
     */
    public void setScanLimit(int limit) {
        Validate.check(limit >= 0, "limit must not be negative");
        this.scanLimit = limit;
    }

    /**
     * @param cellSize the spatial grid's cell edge. Near the radius your modules
     *        query most is best: much smaller and a query walks many empty cells,
     *        much larger and each cell holds everything
     */
    public void setCellSize(double cellSize) {
        Validate.check(cellSize > 0d, "cellSize must be positive");
        this.cellSize = cellSize;
        for (Bucket bucket : buckets) {
            bucket.resize(cellSize);
        }
    }

    /**
     * Reads the whole world again through the source.
     *
     * <p>Entities already known are updated in place, new ones get a new
     * {@link TrackedEntity}, and ones no longer listed are marked
     * {@link TrackedEntity#isTracked() untracked} and dropped.
     */
    public void refresh() {
        EntitySource<Object> current = source;
        if (current == null) {
            return;
        }
        Iterable<?> entities;
        Object selfHandle;
        try {
            entities = current.entities();
            selfHandle = current.self();
        } catch (RuntimeException e) {
            if (!warnedAboutSource) {
                warnedAboutSource = true;
                logger.error("EntitySource threw listing entities; keeping last tick's snapshot"
                        + " (further failures are not logged)", e);
            }
            return;
        }

        generation++;
        all.clear();
        for (Bucket bucket : buckets) {
            bucket.reset();
        }

        self = selfHandle != null ? read(current, selfHandle, true) : null;
        if (entities != null) {
            for (Object handle : entities) {
                if (handle != null && handle != selfHandle) {
                    read(current, handle, false);
                }
            }
        }

        Iterator<TrackedEntity> known = byHandle.values().iterator();
        while (known.hasNext()) {
            TrackedEntity entity = known.next();
            if (entity.lastSeen != generation) {
                entity.removed = true;
                known.remove();
            }
        }
    }

    /** Forgets everything. Called on leaving a world. */
    public void clear() {
        for (TrackedEntity entity : byHandle.values()) {
            entity.removed = true;
        }
        byHandle.clear();
        all.clear();
        for (Bucket bucket : buckets) {
            bucket.reset();
            bucket.grid.clear();
            bucket.gridStale = false;
        }
        self = null;
    }

    // ------------------------------------------------------------- reading

    /** @return the local player, or null when there is none */
    public TrackedEntity getSelf() {
        return self;
    }

    /** @return every entity, the local player included; a live view */
    public List<TrackedEntity> getAll() {
        return allView;
    }

    /** @return every entity in one category; a live view, O(1) */
    public List<TrackedEntity> ofCategory(EntityCategory category) {
        Bucket bucket = existingBucket(category);
        return bucket == null ? Collections.<TrackedEntity>emptyList() : bucket.view;
    }

    /** @return the snapshot of the game's entity object, or null if it is not tracked */
    public TrackedEntity get(Object handle) {
        return handle == null ? null : byHandle.get(handle);
    }

    public int size() {
        return all.size();
    }

    public int count(EntityCategory category) {
        return ofCategory(category).size();
    }

    /** @return how many times the snapshot has been refreshed; changes once a tick */
    public long getGeneration() {
        return generation;
    }

    /**
     * Visits every entity in {@code category} whose box comes within
     * {@code radius} of a point, in no particular order.
     *
     * @param radius measured to the box; {@link Double#POSITIVE_INFINITY} for no limit
     */
    public void forEachWithin(EntityCategory category, double x, double y, double z, double radius,
                              Consumer<? super TrackedEntity> action) {
        Bucket bucket = existingBucket(category);
        if (bucket == null || radius < 0d) {
            return;
        }
        BoxFilter using = begin(x, y, z, radius, action);
        try {
            visit(bucket, x, y, z, radius, using);
        } finally {
            using.end();
        }
    }

    /**
     * Visits every entity in any of {@code categories} whose box comes within
     * {@code radius} of a point. A null collection means every category.
     */
    public void forEachWithin(Iterable<? extends EntityCategory> categories, double x, double y, double z,
                              double radius, Consumer<? super TrackedEntity> action) {
        if (radius < 0d) {
            return;
        }
        BoxFilter using = begin(x, y, z, radius, action);
        try {
            if (categories == null) {
                for (int i = 0; i < buckets.size(); i++) {
                    visit(buckets.get(i), x, y, z, radius, using);
                }
            } else {
                for (EntityCategory category : categories) {
                    Bucket bucket = existingBucket(category);
                    if (bucket != null) {
                        visit(bucket, x, y, z, radius, using);
                    }
                }
            }
        } finally {
            using.end();
        }
    }

    /** Visits every entity, of every category, whose box comes within {@code radius} of a point. */
    public void forEachWithin(double x, double y, double z, double radius, Consumer<? super TrackedEntity> action) {
        forEachWithin((Iterable<EntityCategory>) null, x, y, z, radius, action);
    }

    /** @return the entity in {@code category} whose box is nearest the point within {@code radius}, or null */
    public TrackedEntity nearest(EntityCategory category, double x, double y, double z, double radius) {
        Nearest nearest = new Nearest(x, y, z);
        forEachWithin(category, x, y, z, radius, nearest);
        return nearest.found;
    }

    /** @return how many entities in {@code category} have a box within {@code radius} of the point */
    public int countWithin(EntityCategory category, double x, double y, double z, double radius) {
        int[] count = new int[1];
        forEachWithin(category, x, y, z, radius, entity -> count[0]++);
        return count[0];
    }

    // ------------------------------------------------------------ internals

    private BoxFilter begin(double x, double y, double z, double radius, Consumer<? super TrackedEntity> action) {
        // Something run from inside a query may run another; it gets its own filter.
        BoxFilter using = filter.busy ? new BoxFilter() : filter;
        using.begin(x, y, z, radius, action);
        return using;
    }

    private void visit(Bucket bucket, double x, double y, double z, double radius, BoxFilter using) {
        List<TrackedEntity> members = bucket.members;
        if (members.isEmpty()) {
            return;
        }
        if (members.size() <= scanLimit || Double.isInfinite(radius)) {
            for (int i = 0; i < members.size(); i++) {
                using.accept(members.get(i));
            }
        } else {
            bucket.grid().forEachWithin(x, y, z, radius + bucket.reach, using);
        }
    }

    private Bucket existingBucket(EntityCategory category) {
        if (category == null) {
            return null;
        }
        int slot = Slots.of(category);
        return slot < buckets.size() ? buckets.get(slot) : null;
    }

    private Bucket bucket(int slot) {
        while (buckets.size() <= slot) {
            buckets.add(new Bucket(cellSize));
        }
        return buckets.get(slot);
    }

    private TrackedEntity read(EntitySource<Object> current, Object handle, boolean isSelf) {
        TrackedEntity entity = byHandle.get(handle);
        boolean fresh = entity == null;
        if (fresh) {
            entity = new TrackedEntity(handle);
        }
        boolean continuous = !fresh && entity.lastSeen == generation - 1;
        double lastX = entity.x;
        double lastY = entity.y;
        double lastZ = entity.z;

        writer.begin(entity);
        try {
            current.read(handle, writer);
        } catch (RuntimeException e) {
            if (!warnedAboutRead) {
                warnedAboutRead = true;
                logger.error("EntitySource threw reading " + handle.getClass().getName()
                        + "; skipping it this tick (further failures are not logged)", e);
            }
            return null;
        }
        writer.finish();

        if (continuous) {
            entity.velocityX = entity.x - lastX;
            entity.velocityY = entity.y - lastY;
            entity.velocityZ = entity.z - lastZ;
            entity.ticksTracked++;
        } else {
            entity.velocityX = 0d;
            entity.velocityY = 0d;
            entity.velocityZ = 0d;
            entity.ticksTracked = 0;
        }
        entity.self = isSelf;
        entity.lastSeen = generation;
        entity.removed = false;
        entity.invalidate();

        if (fresh) {
            byHandle.put(handle, entity);
        }
        all.add(entity);
        bucket(entity.categorySlot).add(entity);
        return entity;
    }

    /** One category's members this tick, and the grid over them when one is worth building. */
    private static final class Bucket {

        private final List<TrackedEntity> members = new ArrayList<>();
        private final List<TrackedEntity> view = Collections.unmodifiableList(members);
        private SpatialGrid<TrackedEntity> grid;
        private boolean gridStale = true;

        /** The furthest any member's box reaches from its position, which is how much a position-indexed search must widen. */
        private double reach;

        Bucket(double cellSize) {
            this.grid = SpatialGrid.of(cellSize);
        }

        void resize(double cellSize) {
            grid = SpatialGrid.of(cellSize);
            gridStale = true;
        }

        void reset() {
            members.clear();
            gridStale = true;
            reach = 0d;
        }

        void add(TrackedEntity entity) {
            members.add(entity);
            double dx = Math.max(Math.abs(entity.minX - entity.x), Math.abs(entity.maxX - entity.x));
            double dy = Math.max(Math.abs(entity.minY - entity.y), Math.abs(entity.maxY - entity.y));
            double dz = Math.max(Math.abs(entity.minZ - entity.z), Math.abs(entity.maxZ - entity.z));
            reach = Math.max(reach, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }

        SpatialGrid<TrackedEntity> grid() {
            if (gridStale) {
                grid.clear();
                for (int i = 0; i < members.size(); i++) {
                    TrackedEntity entity = members.get(i);
                    grid.insert(entity.x, entity.y, entity.z, entity);
                }
                gridStale = false;
            }
            return grid;
        }
    }

    /** The one {@link EntityData} the service owns, pointed at each entity in turn. */
    private static final class Writer implements EntityData {

        private TrackedEntity target;
        private double width;
        private double height;
        private boolean explicitBox;

        void begin(TrackedEntity entity) {
            target = entity;
            width = 0d;
            height = 0d;
            explicitBox = false;
            entity.id = -1;
            entity.category = EntityCategory.UNCATEGORIZED;
            entity.categorySlot = Slots.of(EntityCategory.UNCATEGORIZED);
            entity.name = null;
            entity.eyeHeight = 0d;
            entity.yaw = 0f;
            entity.pitch = 0f;
            Arrays.fill(entity.tagWords, 0L);
            Arrays.fill(entity.attributes, Double.NaN);
        }

        void finish() {
            TrackedEntity entity = target;
            if (!explicitBox) {
                double half = width / 2d;
                entity.minX = entity.x - half;
                entity.minY = entity.y;
                entity.minZ = entity.z - half;
                entity.maxX = entity.x + half;
                entity.maxY = entity.y + height;
                entity.maxZ = entity.z + half;
            }
            target = null;
        }

        @Override
        public EntityData id(int id) {
            target.id = id;
            return this;
        }

        @Override
        public EntityData category(EntityCategory category) {
            EntityCategory given = category != null ? category : EntityCategory.UNCATEGORIZED;
            target.category = given;
            target.categorySlot = Slots.of(given);
            return this;
        }

        @Override
        public EntityData name(String name) {
            target.name = name;
            return this;
        }

        @Override
        public EntityData position(double x, double y, double z) {
            target.x = x;
            target.y = y;
            target.z = z;
            return this;
        }

        @Override
        public EntityData size(double width, double height) {
            this.width = Math.max(0d, width);
            this.height = Math.max(0d, height);
            return this;
        }

        @Override
        public EntityData box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            target.minX = Math.min(minX, maxX);
            target.minY = Math.min(minY, maxY);
            target.minZ = Math.min(minZ, maxZ);
            target.maxX = Math.max(minX, maxX);
            target.maxY = Math.max(minY, maxY);
            target.maxZ = Math.max(minZ, maxZ);
            explicitBox = true;
            return this;
        }

        @Override
        public EntityData eyeHeight(double eyeHeight) {
            target.eyeHeight = eyeHeight;
            return this;
        }

        @Override
        public EntityData rotation(float yaw, float pitch) {
            target.yaw = yaw;
            target.pitch = pitch;
            return this;
        }

        @Override
        public EntityData tag(EntityTag tag) {
            int slot = Slots.of(tag);
            int word = slot >>> 6;
            if (word >= target.tagWords.length) {
                target.tagWords = Arrays.copyOf(target.tagWords, word + 1);
            }
            target.tagWords[word] |= 1L << slot;
            return this;
        }

        @Override
        public EntityData tag(EntityTag tag, boolean present) {
            return present ? tag(tag) : this;
        }

        @Override
        public EntityData set(EntityAttribute attribute, double value) {
            int slot = Slots.of(attribute);
            if (slot >= target.attributes.length) {
                int old = target.attributes.length;
                target.attributes = Arrays.copyOf(target.attributes, slot + 1);
                Arrays.fill(target.attributes, old, target.attributes.length, Double.NaN);
            }
            target.attributes[slot] = value;
            return this;
        }
    }

    /** Applies the exact box test to candidates, reused across queries so a query allocates nothing. */
    private static final class BoxFilter implements Consumer<TrackedEntity> {

        private double x;
        private double y;
        private double z;
        private double squaredRadius;
        private Consumer<? super TrackedEntity> action;
        private boolean busy;

        void begin(double x, double y, double z, double radius, Consumer<? super TrackedEntity> action) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.squaredRadius = radius * radius;
            this.action = action;
            this.busy = true;
        }

        void end() {
            action = null;
            busy = false;
        }

        @Override
        public void accept(TrackedEntity entity) {
            if (entity.squaredDistanceToBox(x, y, z) <= squaredRadius) {
                action.accept(entity);
            }
        }
    }

    private static final class Nearest implements Consumer<TrackedEntity> {

        private final double x;
        private final double y;
        private final double z;
        private double best = Double.MAX_VALUE;
        private TrackedEntity found;

        Nearest(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void accept(TrackedEntity entity) {
            double distance = entity.squaredDistanceToBox(x, y, z);
            if (distance < best) {
                best = distance;
                found = entity;
            }
        }
    }

    private final class Listener {

        // Ahead of every module's tick handler, so they all read this tick's world.
        @Subscribe(stage = Stage.PRE, priority = Integer.MAX_VALUE - 16)
        private void onTick(TickEvent event) {
            if (autoRefresh) {
                refresh();
            }
        }

        @Subscribe
        private void onWorld(WorldEvent event) {
            if (event.isUnloaded()) {
                clear();
            }
        }
    }
}
