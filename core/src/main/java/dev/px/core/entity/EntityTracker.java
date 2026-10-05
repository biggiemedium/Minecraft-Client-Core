package dev.px.core.entity;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.spatial.SpatialGrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keeps track of every entity of one type: one {@link Tracked} each, updated
 * every tick, and indexed so "what is near here" does not ask everything.
 *
 * <p>The type is the game's own, so a tracker hands back the real object and
 * nothing is cast or copied into a vocabulary of Core's:
 *
 * <pre>{@code
 * public final class CrystalTracker extends EntityTracker<EntityEnderCrystal> {
 *     public CrystalTracker() { super(EntityEnderCrystal.class); }
 * }
 *
 * public final class EnemyTracker extends EntityTracker<EntityPlayer> {
 *     public EnemyTracker() { super(EntityPlayer.class); }
 *
 *     @Override protected boolean accepts(EntityPlayer p) {
 *         return !p.isDead && !Core.social().isFriend(p.getName());
 *     }
 * }
 *
 * Core.entities().registerAll(new CrystalTracker(), new EnemyTracker());
 *
 * // anywhere, by class, like a module
 * Tracked<EntityEnderCrystal> crystal = Core.entities().get(CrystalTracker.class).nearest(6);
 * }</pre>
 *
 * <p>Subclass for a tracker other code looks up by class, or that overrides
 * {@link #accepts}, {@link #onTracked} or {@link #onUntracked}. For one a single
 * module owns, {@link #of(Class, Predicate)} needs no class at all; it still has
 * to be {@linkplain EntityService#register registered} to be fed.
 *
 * <p>The type may be a class or an interface, and a subclass of it is tracked
 * too: an {@code EntityTracker<EntityLivingBase>} sees players and monsters
 * alike. Trackers can overlap freely; {@link EntityService} reads the world once
 * and hands each entity to every tracker whose type it is, so a player in three
 * trackers is read once and held three times, once per tracker. The local player
 * is never in a tracker: it is {@link EntityService#getSelf()}.
 *
 * <h2>Between ticks</h2>
 *
 * <p>The world is read at the start of every tick. Something that must react the
 * moment the game learns of an entity &mdash; a crystal from its spawn packet
 * &mdash; calls {@link #track} from the packet handler, and {@link #forget} when
 * it is destroyed, rather than waiting for the next tick to notice.
 *
 * <h2>Cost</h2>
 *
 * <p>With n entities of this type and a query visiting k:
 *
 * <pre>
 * refresh           O(n)          once a tick; nothing allocated for entities already tracked
 * get, contains     O(1)
 * forEachWithin     O(n)          for a small tracker (see {@link #setScanLimit}), a plain scan
 *                   O(b + k)      otherwise, through a spatial grid: b buckets the radius covers
 * </pre>
 *
 * <p>The grid is built on the first range query of the tick that needs it, and
 * not at all on a tick with none. Distances are to the <b>box</b>, not the
 * position: the grid indexes positions, so the search radius is widened by the
 * furthest any box reached from its position that tick, and every candidate is
 * then measured exactly.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what this tracker holds
 */
public class EntityTracker<E> {

    /** A tracker with at most this many entities is scanned rather than indexed, until {@link #setScanLimit} says otherwise. */
    public static final int DEFAULT_SCAN_LIMIT = 32;

    /** Grid cell edge until {@link #setCellSize} says otherwise. A tuning knob, not a fact about any game. */
    public static final double DEFAULT_CELL_SIZE = 8d;

    private final Class<E> type;
    private final Predicate<? super E> filter;

    private final Map<Object, Tracked<E>> byHandle = new IdentityHashMap<>();
    private final List<Tracked<E>> members = new ArrayList<>();
    private final List<Tracked<E>> view = Collections.unmodifiableList(members);
    private final List<Tracked<E>> leaving = new ArrayList<>();
    private final BoxFilter boxFilter = new BoxFilter();

    private EntityService service;
    private SpatialGrid<Tracked<E>> grid = SpatialGrid.of(DEFAULT_CELL_SIZE);
    private boolean gridStale = true;
    /** The furthest any member's box reaches from its position, which is how much a position-indexed search must widen. */
    private double reach;
    private int scanLimit = DEFAULT_SCAN_LIMIT;

    /**
     * @param type the game's class or interface for what this tracker holds;
     *        needed because Java forgets {@code E} at runtime
     */
    protected EntityTracker(Class<E> type) {
        this(type, null);
    }

    private EntityTracker(Class<E> type, Predicate<? super E> filter) {
        this.type = Validate.notNull(type, "type");
        this.filter = filter;
    }

    /** @return a tracker of every entity of {@code type} */
    public static <E> EntityTracker<E> of(Class<E> type) {
        return new EntityTracker<>(type, null);
    }

    /** @return a tracker of the entities of {@code type} that pass {@code accepts}, asked every tick */
    public static <E> EntityTracker<E> of(Class<E> type, Predicate<? super E> accepts) {
        return new EntityTracker<>(type, Validate.notNull(accepts, "accepts"));
    }

    // ----------------------------------------------------- for subclasses

    /**
     * Whether to track this entity this tick. Asked every tick for every entity
     * of the type, so an entity that stops passing is let go and one that starts
     * is picked up; keep it cheap.
     *
     * <p>Every entity of the type, unless overridden or given to {@link #of(Class, Predicate)}.
     */
    protected boolean accepts(E entity) {
        return filter == null || filter.test(entity);
    }

    /** Called once when an entity starts being tracked, after its first reading. */
    protected void onTracked(Tracked<E> entity) {
    }

    /**
     * Called once when an entity stops being tracked: it left the world, stopped
     * being accepted, was {@linkplain #forget forgotten}, or the world unloaded.
     * {@link Tracked#isTracked()} is already false.
     */
    protected void onUntracked(Tracked<E> entity) {
    }

    // ------------------------------------------------------------- reading

    public Class<E> getType() {
        return type;
    }

    /** @return whether an {@link EntityService} is feeding this tracker */
    public boolean isRegistered() {
        return service != null;
    }

    /** @return every entity tracked, in the order the world listed them; a live view */
    public List<Tracked<E>> getAll() {
        return view;
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** @return the snapshot of the game's object, or null if this tracker does not hold it */
    public Tracked<E> get(Object entity) {
        return entity == null ? null : byHandle.get(entity);
    }

    /** @return whether this tracker holds this snapshot right now. O(1) */
    public boolean contains(Tracked<?> entity) {
        return entity != null && byHandle.get(entity.handle) == entity;
    }

    /**
     * Visits every entity whose box comes within {@code radius} of a point, in no
     * particular order.
     *
     * @param radius measured to the box; {@link Double#POSITIVE_INFINITY} for no limit
     */
    public void forEachWithin(double x, double y, double z, double radius, Consumer<? super Tracked<E>> action) {
        Validate.notNull(action, "action");
        if (radius < 0d || members.isEmpty()) {
            return;
        }
        // Something run from inside a query may run another; it gets its own filter.
        BoxFilter using = boxFilter.busy ? new BoxFilter() : boxFilter;
        using.begin(x, y, z, radius, action);
        try {
            if (members.size() <= scanLimit || Double.isInfinite(radius)) {
                for (int i = 0; i < members.size(); i++) {
                    using.accept(members.get(i));
                }
            } else {
                grid().forEachWithin(x, y, z, radius + reach, using);
            }
        } finally {
            using.end();
        }
    }

    public void forEachWithin(Vec3 point, double radius, Consumer<? super Tracked<E>> action) {
        forEachWithin(point.getX(), point.getY(), point.getZ(), radius, action);
    }

    /** @return the entity whose box is nearest the point, within {@code radius}, or null */
    public Tracked<E> nearest(double x, double y, double z, double radius) {
        Nearest nearest = new Nearest(x, y, z);
        forEachWithin(x, y, z, radius, nearest);
        return nearest.found;
    }

    public Tracked<E> nearest(Vec3 point, double radius) {
        return nearest(point.getX(), point.getY(), point.getZ(), radius);
    }

    /** @return the entity whose box is nearest the local player's eyes, within {@code radius}, or null with no local player */
    public Tracked<E> nearest(double radius) {
        Tracked<?> self = service == null ? null : service.getSelf();
        return self == null ? null : nearest(self.getX(), self.getEyeY(), self.getZ(), radius);
    }

    /** @return how many entities have a box within {@code radius} of the point */
    public int countWithin(double x, double y, double z, double radius) {
        int[] count = new int[1];
        forEachWithin(x, y, z, radius, entity -> count[0]++);
        return count[0];
    }

    // ------------------------------------------------------- between ticks

    /**
     * Tracks an entity now instead of at the next tick. For a packet handler that
     * has just learned of it.
     *
     * <p>An entity already tracked is returned as it is: its reading changes at
     * the tick, so its velocity stays a per-tick one.
     *
     * @return the snapshot, or null when this tracker would not hold it: wrong
     *         type, not {@linkplain #accepts accepted}, the local player, the
     *         source could not read it, or the tracker is not registered
     */
    public Tracked<E> track(E entity) {
        if (entity == null || service == null || !type.isInstance(entity) || service.isSelf(entity)) {
            return null;
        }
        Tracked<E> existing = byHandle.get(entity);
        if (existing != null) {
            return existing;
        }
        Reading reading = service.readNow(entity);
        if (reading == null || !safeAccepts(entity)) {
            return null;
        }
        return add(entity, reading, service.getTick());
    }

    /**
     * Stops tracking an entity now instead of at the next tick. For a packet
     * handler that has just seen it destroyed. If the world still lists it next
     * tick, it is tracked again as a new snapshot.
     *
     * @return whether it was tracked
     */
    public boolean forget(E entity) {
        Tracked<E> tracked = entity == null ? null : byHandle.remove(entity);
        if (tracked == null) {
            return false;
        }
        members.remove(tracked);
        gridStale = true;
        untrack(tracked);
        return true;
    }

    // -------------------------------------------------------------- tuning

    /**
     * @param limit a tracker with at most this many entities is scanned rather
     *        than indexed; scanning a few is cheaper than building a grid for them
     */
    public EntityTracker<E> setScanLimit(int limit) {
        Validate.check(limit >= 0, "limit must not be negative");
        this.scanLimit = limit;
        return this;
    }

    /**
     * @param cellSize the spatial grid's cell edge. Near the radius you query most
     *        is best: much smaller and a query walks many empty cells, much larger
     *        and each cell holds everything
     */
    public EntityTracker<E> setCellSize(double cellSize) {
        Validate.check(cellSize > 0d, "cellSize must be positive");
        this.grid = SpatialGrid.of(cellSize);
        this.gridStale = true;
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "<" + type.getSimpleName() + ">(" + members.size() + ")";
    }

    // ------------------------------------------- driven by EntityService

    void attach(EntityService owner) {
        Validate.check(service == null, this + " is already registered");
        service = owner;
    }

    void detach() {
        clear();
        service = null;
    }

    /** Opens a refresh: membership is rebuilt from what the world lists. */
    void begin() {
        members.clear();
        gridStale = true;
        reach = 0d;
    }

    /** Offers one entity of this tracker's type, already read. */
    @SuppressWarnings("unchecked")
    void offer(Object handle, Reading reading, long tick) {
        E entity = (E) handle;
        if (!safeAccepts(entity)) {
            return;
        }
        Tracked<E> tracked = byHandle.get(handle);
        if (tracked == null) {
            add(entity, reading, tick);
        } else {
            tracked.update(reading, tick);
            members.add(tracked);
            widen(tracked);
        }
    }

    /** Closes a refresh: whatever was not offered this tick is let go. */
    void end(long tick) {
        Iterator<Tracked<E>> known = byHandle.values().iterator();
        while (known.hasNext()) {
            Tracked<E> tracked = known.next();
            if (tracked.lastSeen != tick) {
                known.remove();
                leaving.add(tracked);
            }
        }
        // Hooks run after the sweep, so one may query the tracker safely.
        try {
            for (int i = 0; i < leaving.size(); i++) {
                untrack(leaving.get(i));
            }
        } finally {
            leaving.clear();
        }
    }

    /** Forgets everything. */
    void clear() {
        leaving.addAll(byHandle.values());
        byHandle.clear();
        members.clear();
        grid.clear();
        gridStale = true;
        reach = 0d;
        try {
            for (int i = 0; i < leaving.size(); i++) {
                untrack(leaving.get(i));
            }
        } finally {
            leaving.clear();
        }
    }

    // ------------------------------------------------------------ internals

    private Tracked<E> add(E entity, Reading reading, long tick) {
        Tracked<E> tracked = new Tracked<>(entity);
        tracked.update(reading, tick);
        byHandle.put(entity, tracked);
        members.add(tracked);
        widen(tracked);
        try {
            onTracked(tracked);
        } catch (RuntimeException e) {
            failed("onTracked", e);
        }
        return tracked;
    }

    private void untrack(Tracked<E> tracked) {
        tracked.removed = true;
        try {
            onUntracked(tracked);
        } catch (RuntimeException e) {
            failed("onUntracked", e);
        }
    }

    private boolean safeAccepts(E entity) {
        try {
            return accepts(entity);
        } catch (RuntimeException e) {
            failed("accepts", e);
            return false;
        }
    }

    private void failed(String where, RuntimeException e) {
        if (service != null) {
            service.trackerFailed(this, where, e);
        }
    }

    private void widen(Tracked<E> entity) {
        gridStale = true;
        double dx = Math.max(Math.abs(entity.minX - entity.x), Math.abs(entity.maxX - entity.x));
        double dy = Math.max(Math.abs(entity.minY - entity.y), Math.abs(entity.maxY - entity.y));
        double dz = Math.max(Math.abs(entity.minZ - entity.z), Math.abs(entity.maxZ - entity.z));
        reach = Math.max(reach, Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private SpatialGrid<Tracked<E>> grid() {
        if (gridStale) {
            grid.clear();
            for (int i = 0; i < members.size(); i++) {
                Tracked<E> entity = members.get(i);
                grid.insert(entity.x, entity.y, entity.z, entity);
            }
            gridStale = false;
        }
        return grid;
    }

    /** Applies the exact box test to candidates, reused across queries so a query allocates nothing. */
    private final class BoxFilter implements Consumer<Tracked<E>> {

        private double x;
        private double y;
        private double z;
        private double squaredRadius;
        private Consumer<? super Tracked<E>> action;
        private boolean busy;

        void begin(double x, double y, double z, double radius, Consumer<? super Tracked<E>> action) {
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
        public void accept(Tracked<E> entity) {
            if (entity.squaredDistanceToBox(x, y, z) <= squaredRadius) {
                action.accept(entity);
            }
        }
    }

    private final class Nearest implements Consumer<Tracked<E>> {

        private final double x;
        private final double y;
        private final double z;
        private double best = Double.MAX_VALUE;
        private Tracked<E> found;

        Nearest(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void accept(Tracked<E> entity) {
            double distance = entity.squaredDistanceToBox(x, y, z);
            if (distance < best) {
                best = distance;
                found = entity;
            }
        }
    }
}
