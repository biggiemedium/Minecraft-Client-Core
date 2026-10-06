package dev.px.core.entity;

import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The world, read once a tick through your {@link EntitySource} and handed out to
 * the {@link EntityTracker}s that want each entity.
 *
 * <pre>{@code
 * Core.entities().setSource(new LegacyEntities());               // once, from the adapter
 * Core.entities().registerAll(new EnemyTracker(), new CrystalTracker());
 *
 * Tracked<EntityPlayer> nearest = Core.entities().get(EnemyTracker.class).nearest(6);
 * Tracked<?> self = Core.entities().getSelf();
 * }</pre>
 *
 * <p>Core has no idea what any entity is. A tracker's type decides what it
 * holds; this service only lists the world, reads each entity's geometry once,
 * and routes it by class. The route for each concrete class is worked out on
 * first sight and cached, so an entity no tracker wants costs one map lookup
 * and is never read at all.
 *
 * <p>The world is refreshed at the start of every tick, ahead of every module, so
 * they all read the same one. {@link #setAutoRefresh(boolean)} hands that timing
 * to you.
 *
 * <p>Game thread only.
 */
public final class EntityService implements Service {

    private static final EntityTracker<?>[] NONE = new EntityTracker<?>[0];

    private final CoreLogger logger;
    private final EventBus bus;
    private final Listener listener = new Listener();
    private final Reading reading = new Reading();
    /** For {@link EntityTracker#track}, which may run from a hook in the middle of a refresh using {@link #reading}. */
    private final Reading immediate = new Reading();

    private final List<EntityTracker<?>> trackers = new ArrayList<>();
    private final List<EntityTracker<?>> trackersView = Collections.unmodifiableList(trackers);
    private final Map<Class<?>, EntityTracker<?>> byClass = new LinkedHashMap<>();
    private final Map<Class<?>, EntityTracker<?>[]> routes = new HashMap<>();

    private EntitySource<Object> source;
    private Object selfHandle;
    private Tracked<Object> self;
    private long tick;
    private boolean autoRefresh = true;
    private boolean warnedAboutSource;
    private boolean warnedAboutRead;
    private boolean warnedAboutTracker;

    public EntityService(CoreLogger logger, EventBus bus) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
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

    // ------------------------------------------------------------ trackers

    /**
     * Starts feeding a tracker from the next refresh. Any time is fine, before or
     * after {@code start()}.
     *
     * @throws IllegalArgumentException if it is registered already, or another
     *         tracker of the same subclass is
     */
    public <T extends EntityTracker<?>> T register(T tracker) {
        Validate.notNull(tracker, "tracker");
        Class<?> key = tracker.getClass();
        boolean keyed = key != EntityTracker.class;
        Validate.check(!keyed || !byClass.containsKey(key), "a " + key.getName() + " is already registered");
        tracker.attach(this);
        trackers.add(tracker);
        if (keyed) {
            byClass.put(key, tracker);
        }
        routes.clear();
        return tracker;
    }

    public void registerAll(EntityTracker<?>... trackers) {
        for (EntityTracker<?> tracker : trackers) {
            register(tracker);
        }
    }

    /** Stops feeding a tracker; it forgets everything it held. */
    public boolean unregister(EntityTracker<?> tracker) {
        if (tracker == null || !trackers.remove(tracker)) {
            return false;
        }
        byClass.remove(tracker.getClass(), tracker);
        routes.clear();
        tracker.detach();
        return true;
    }

    /**
     * @return the registered tracker of exactly this class, or null. Trackers made
     *         with {@link EntityTracker#of} have no class of their own and are not
     *         found here
     */
    @SuppressWarnings("unchecked")
    public <T extends EntityTracker<?>> T get(Class<T> type) {
        return (T) byClass.get(type);
    }

    /** @throws IllegalStateException if no tracker of this class is registered */
    public <T extends EntityTracker<?>> T require(Class<T> type) {
        T tracker = get(type);
        if (tracker == null) {
            throw new IllegalStateException("no " + type.getName() + " is registered");
        }
        return tracker;
    }

    /** @return every registered tracker, in registration order */
    public List<EntityTracker<?>> getTrackers() {
        return trackersView;
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
     * Whether the world is refreshed at the start of every tick, ahead of every
     * module. On by default. Turn it off to call {@link #refresh()} yourself at a
     * better point in your version's loop.
     */
    public void setAutoRefresh(boolean autoRefresh) {
        this.autoRefresh = autoRefresh;
    }

    // ------------------------------------------------------------- reading

    /**
     * @return the local player, or null when there is none. Typed as the caller
     *         expects: {@code Tracked<EntityPlayerSP> self = Core.entities().getSelf();}
     */
    @SuppressWarnings("unchecked")
    public <E> Tracked<E> getSelf() {
        return (Tracked<E>) self;
    }

    /** @return how many times the world has been refreshed; changes once a tick */
    public long getTick() {
        return tick;
    }

    /**
     * Reads the whole world again and hands every entity to the trackers whose
     * type it is.
     *
     * <p>Entities a tracker already holds are updated in place, new ones get a
     * new {@link Tracked}, and ones no longer listed or accepted are let go.
     */
    public void refresh() {
        EntitySource<Object> current = source;
        if (current == null) {
            return;
        }
        Iterable<?> entities;
        Object currentSelf;
        try {
            entities = current.entities();
            currentSelf = current.self();
        } catch (RuntimeException e) {
            if (!warnedAboutSource) {
                warnedAboutSource = true;
                logger.error("EntitySource threw listing entities; keeping last tick's world"
                        + " (further failures are not logged)", e);
            }
            return;
        }

        tick++;
        refreshSelf(current, currentSelf);
        for (int i = 0; i < trackers.size(); i++) {
            trackers.get(i).begin();
        }
        if (entities != null) {
            for (Object entity : entities) {
                if (entity == null || entity == currentSelf) {
                    continue;
                }
                EntityTracker<?>[] route = route(entity.getClass());
                if (route.length == 0 || !read(current, entity, reading)) {
                    continue;
                }
                for (EntityTracker<?> tracker : route) {
                    tracker.offer(entity, reading, tick);
                }
            }
        }
        for (int i = 0; i < trackers.size(); i++) {
            trackers.get(i).end(tick);
        }
    }

    /** Forgets everything every tracker holds, and the local player. Called on leaving a world. */
    public void clear() {
        for (int i = 0; i < trackers.size(); i++) {
            trackers.get(i).clear();
        }
        if (self != null) {
            self.removed = true;
        }
        self = null;
        selfHandle = null;
    }

    // ------------------------------------------------ for EntityTracker

    boolean isSelf(Object entity) {
        return entity == selfHandle;
    }

    /** @return the entity read now, or null when there is no source or it threw */
    Reading readNow(Object entity) {
        EntitySource<Object> current = source;
        return current != null && read(current, entity, immediate) ? immediate : null;
    }

    void trackerFailed(EntityTracker<?> tracker, String where, RuntimeException e) {
        if (!warnedAboutTracker) {
            warnedAboutTracker = true;
            logger.error(tracker + " threw in " + where + "; skipping that entity"
                    + " (further failures are not logged)", e);
        }
    }

    // ------------------------------------------------------------ internals

    private void refreshSelf(EntitySource<Object> current, Object handle) {
        if (handle != selfHandle && self != null) {
            self.removed = true;
            self = null;
        }
        selfHandle = handle;
        if (handle == null || !read(current, handle, reading)) {
            if (self != null) {
                self.removed = true;
            }
            self = null;
            return;
        }
        if (self == null) {
            self = new Tracked<>(handle, EntityTracker.DEFAULT_HISTORY, EntityTracker.DEFAULT_UPDATE_GAP);
        }
        self.update(reading, tick);
    }

    private boolean read(EntitySource<Object> current, Object entity, Reading into) {
        try {
            into.read(current, entity);
            return true;
        } catch (RuntimeException e) {
            if (!warnedAboutRead) {
                warnedAboutRead = true;
                logger.error("EntitySource threw reading " + entity.getClass().getName()
                        + "; skipping it this tick (further failures are not logged)", e);
            }
            return false;
        }
    }

    /** @return the trackers an entity of this class goes to, worked out once per class */
    private EntityTracker<?>[] route(Class<?> type) {
        EntityTracker<?>[] route = routes.get(type);
        if (route == null) {
            List<EntityTracker<?>> wanting = new ArrayList<>();
            for (EntityTracker<?> tracker : trackers) {
                if (tracker.getType().isAssignableFrom(type)) {
                    wanting.add(tracker);
                }
            }
            route = wanting.isEmpty() ? NONE : wanting.toArray(new EntityTracker<?>[0]);
            routes.put(type, route);
        }
        return route;
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
