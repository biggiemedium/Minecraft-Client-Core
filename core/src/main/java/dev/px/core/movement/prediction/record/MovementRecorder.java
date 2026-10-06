package dev.px.core.movement.prediction.record;

import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.math.Box;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Records how real entities move, for {@link MovementReplay} to put predictions
 * back in the same place and score them.
 *
 * <pre>{@code
 * MovementRecorder recorder = new MovementRecorder(Core.prediction())
 *         .names(entity -> ((PlayerEntity) entity).getName().getString());
 *
 * recorder.begin("strafe vs legit", Core.entities().get(EnemyTracker.class));
 * recorder.bus(Core.bus());                       // or call recorder.tick() after each entity refresh
 * // ... play ...
 * MovementRecording recording = recorder.end();
 * MovementJson.write(recording, Files.newBufferedWriter(path));
 * }</pre>
 *
 * <p>Each tick it keeps, for every entity the given trackers hold: where it is,
 * which way it faces, how big it is, and the stamp your source gave &mdash;
 * exactly what the prediction reads, so a replay sees what the live client did.
 * It also keeps the rules your {@code EntityPhysics} said each entity moved by,
 * when they change, and the collision boxes around each entity, in cubes of
 * {@link #setSectionSize section size}, re-read every few ticks so blocks placed
 * and broken are kept too.
 *
 * <p>A development tool: reading the world every few ticks around every recorded
 * entity is not free. Record the few players you care about, not the server.
 *
 * <p>Game thread only.
 */
public final class MovementRecorder {

    /** Edge of the cubes the world is captured in, in blocks, unless {@link #setSectionSize} says otherwise. */
    public static final int DEFAULT_SECTION_SIZE = 8;

    /** Blocks around each entity the world is captured to, unless {@link #setReach} says otherwise. */
    public static final double DEFAULT_REACH = 4d;

    /** Ticks between re-reading a captured cube, unless {@link #setRefresh} says otherwise. */
    public static final int DEFAULT_REFRESH = 5;

    private final PredictionService prediction;
    private final Listener listener = new Listener();
    private Function<Object, String> names;
    private int sectionSize = DEFAULT_SECTION_SIZE;
    private double reach = DEFAULT_REACH;
    private int refresh = DEFAULT_REFRESH;
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private boolean recording;
    private String label;
    private List<EntityTracker<?>> trackers;
    private long tick;
    private PhysicsProfile base;
    private Map<Tracked<?>, MovementRecording.Track> tracks;
    private List<MovementRecording.Track> order;
    private Map<Integer, Map<String, Double>> lastRules;
    private Map<Integer, Boolean> lastModelled;
    private Map<Long, List<Box>> captured;
    private Map<Long, Long> capturedAt;
    private List<MovementRecording.Blocks> blocks;
    private EventBus bus;

    /** @param prediction whose rules, simulation profile and world to record */
    public MovementRecorder(PredictionService prediction) {
        this.prediction = Validate.notNull(prediction, "prediction");
    }

    /** What to call each entity, from the game's object: a player's name, say. Optional. */
    public MovementRecorder names(Function<Object, String> names) {
        this.names = names;
        return this;
    }

    public void setSectionSize(int blocks) {
        Validate.check(blocks >= 1, "section size must be at least 1");
        requireIdle();
        this.sectionSize = blocks;
    }

    /** @param blocks how far around each entity the world is captured */
    public void setReach(double blocks) {
        Validate.check(blocks >= 0d, "reach must not be negative");
        this.reach = blocks;
    }

    /** @param ticks how often a captured cube is read again, to catch blocks placed and broken */
    public void setRefresh(int ticks) {
        Validate.check(ticks >= 1, "refresh must be at least 1 tick");
        this.refresh = ticks;
    }

    /** Kept with the recording: the server, the version, who was cheating. */
    public void putMetadata(String key, String value) {
        Validate.notNull(key, "key");
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * Starts recording every entity {@code trackers} hold, from the next tick.
     *
     * @throws IllegalStateException if already recording
     */
    public void begin(String label, EntityTracker<?>... trackers) {
        Validate.notNull(label, "label");
        Validate.check(trackers.length > 0, "at least one tracker to record is needed");
        requireIdle();
        this.label = label;
        this.trackers = new ArrayList<>(Arrays.asList(trackers));
        this.tick = 0L;
        this.base = prediction.getSimulation().getProfile();
        this.tracks = new IdentityHashMap<>();
        this.order = new ArrayList<>();
        this.lastRules = new HashMap<>();
        this.lastModelled = new HashMap<>();
        this.captured = new HashMap<>();
        this.capturedAt = new HashMap<>();
        this.blocks = new ArrayList<>();
        this.recording = true;
    }

    /** Ticks itself on {@code bus}'s {@code TickEvent}, after the entities are read, until {@link #end}. */
    public void bus(EventBus bus) {
        if (this.bus != null) {
            this.bus.unsubscribe(listener);
        }
        this.bus = Validate.notNull(bus, "bus");
        bus.subscribe(listener);
    }

    /** Records this tick. Call it after the entities were read; {@link #bus} does. Nothing while not recording. */
    public void tick() {
        if (!recording) {
            return;
        }
        tick++;
        CollisionSpace world = prediction.getSimulation().getCollisionSpace();
        for (EntityTracker<?> tracker : trackers) {
            for (Tracked<?> entity : tracker.getAll()) {
                MovementRecording.Track track = tracks.get(entity);
                if (track == null) {
                    track = new MovementRecording.Track(order.size() + 1,
                            names == null ? null : names.apply(entity.get()), tracker.getHistory(), tracker.getUpdateGap());
                    tracks.put(entity, track);
                    order.add(track);
                }
                track.samples.add(new MovementRecording.Sample(tick, entity.getX(), entity.getY(), entity.getZ(),
                        entity.getYaw(), entity.getWidth(), entity.getHeight(), entity.getPositionStamp()));
                rules(track, prediction.rulesOf(entity));
                capture(world, entity);
            }
        }
    }

    /**
     * Stops recording and hands back everything recorded.
     *
     * @throws IllegalStateException if not recording
     */
    public MovementRecording end() {
        if (!recording) {
            throw new IllegalStateException("not recording");
        }
        if (bus != null) {
            bus.unsubscribe(listener);
            bus = null;
        }
        recording = false;
        MovementRecording recording = new MovementRecording(label, metadata, tick, sectionSize, base, order, blocks);
        tracks = null;
        order = null;
        captured = null;
        capturedAt = null;
        blocks = null;
        return recording;
    }

    private void rules(MovementRecording.Track track, PhysicsProfile profile) {
        Map<String, Double> values = profile == null ? null : profile.toMap();
        Boolean wasModelled = lastModelled.get(track.getId());
        if (wasModelled != null && wasModelled == (profile != null)
                && (values == null || values.equals(lastRules.get(track.getId())))) {
            return;
        }
        lastModelled.put(track.getId(), profile != null);
        lastRules.put(track.getId(), values);
        track.rules.add(new MovementRecording.Rules(tick, profile));
    }

    /** Captures each cube around {@code entity} not yet captured, or due to be read again. */
    private void capture(CollisionSpace world, Tracked<?> entity) {
        int minX = section(entity.getMinX() - reach);
        int maxX = section(entity.getMaxX() + reach);
        int minY = section(entity.getMinY() - reach);
        int maxY = section(entity.getMaxY() + reach);
        int minZ = section(entity.getMinZ() - reach);
        int maxZ = section(entity.getMaxZ() + reach);
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    long key = key(sx, sy, sz);
                    Long at = capturedAt.get(key);
                    if (at != null && tick - at < refresh) {
                        continue;
                    }
                    capturedAt.put(key, tick);
                    Box cube = Box.of(sx * (double) sectionSize, sy * (double) sectionSize, sz * (double) sectionSize,
                            (sx + 1) * (double) sectionSize, (sy + 1) * (double) sectionSize,
                            (sz + 1) * (double) sectionSize);
                    List<Box> found = world.boxesIn(cube);
                    List<Box> boxes = found == null ? new ArrayList<Box>() : new ArrayList<>(found);
                    if (!boxes.equals(captured.get(key))) {
                        captured.put(key, boxes);
                        blocks.add(new MovementRecording.Blocks(tick, sx, sy, sz, boxes));
                    }
                }
            }
        }
    }

    private int section(double coordinate) {
        return (int) Math.floor(coordinate / sectionSize);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
    }

    private void requireIdle() {
        if (recording) {
            throw new IllegalStateException("already recording \"" + label + "\"");
        }
    }

    /** Subscribed only while a bus drives it. */
    private final class Listener {

        @Subscribe(stage = Stage.PRE, priority = Integer.MAX_VALUE - 48)
        private void onTick(TickEvent event) {
            tick();
        }
    }
}
