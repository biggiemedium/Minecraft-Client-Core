package dev.px.core.movement.prediction.record;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Behaviour;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Puts predictions back where a {@link MovementRecording} was made and scores
 * them against what really happened.
 *
 * <pre>{@code
 * MovementRecording recording = MovementJson.read(Files.newBufferedReader(path));
 * ReplayReport report = MovementReplay.of(recording)
 *         .horizons(1, 5, 10)
 *         .configure(prediction -> prediction.setBacktest(6))    // the settings you want to try
 *         .run();
 * System.out.println(report);
 * }</pre>
 *
 * <p>Each recorded tick goes through a real {@code EntityService} and
 * {@code PredictionService}, fed the recorded positions and stamps, the rules
 * your client gave, and the world as it was captured at that tick: the same
 * inputs the live client had. Every tick, every entity is predicted as far as the
 * longest horizon, and each horizon is scored against the position the server
 * really sent that many ticks later; ticks with no news then are not scored.
 *
 * <p>The possible bound is scored the strict way: each entity's real box at the
 * horizon must not be somewhere the bound said it could not yet be. A late bound
 * is an entity moving faster than it had yet been seen to.
 */
public final class MovementReplay {

    private final MovementRecording recording;
    private int[] horizons = { 1, 5, 10, 20 };
    private Consumer<PredictionService> setup = prediction -> { };

    private MovementReplay(MovementRecording recording) {
        this.recording = recording;
    }

    public static MovementReplay of(MovementRecording recording) {
        return new MovementReplay(Validate.notNull(recording, "recording"));
    }

    /** @param ticks how far ahead to score; 1, 5, 10 and 20 unless set */
    public MovementReplay horizons(int... ticks) {
        Validate.check(ticks.length > 0, "at least one horizon is needed");
        for (int tick : ticks) {
            Validate.check(tick >= 1, "a horizon must be at least 1 tick");
        }
        int[] sorted = ticks.clone();
        Arrays.sort(sorted);
        this.horizons = sorted;
        return this;
    }

    /**
     * @param setup the settings and scenarios to replay with. The rules each
     *        entity moved by come from the recording, and replace any physics set here
     */
    public MovementReplay configure(Consumer<PredictionService> setup) {
        this.setup = Validate.notNull(setup, "setup");
        return this;
    }

    public ReplayReport run() {
        CoreLogger logger = new Silent();
        EntityService entities = new EntityService(logger, new CoreEventBus(logger));
        Map<MovementRecording.Track, Replayed> replayed = new LinkedHashMap<>();
        Map<MovementRecording.Track, EntityTracker<Replayed>> trackers = new HashMap<>();
        for (MovementRecording.Track track : recording.getTracks()) {
            replayed.put(track, new Replayed(track));
            EntityTracker<Replayed> tracker = EntityTracker.of(Replayed.class, entity -> entity.track == track)
                    .setHistory(track.getHistory()).setUpdateGap(track.getUpdateGap());
            trackers.put(track, entities.register(tracker));
        }
        entities.setSource(new Source(replayed));

        World world = new World(recording);
        SimulationService simulation = new SimulationService(logger, new CoreEventBus(logger));
        simulation.setProfile(recording.getBase());
        simulation.setCollisionSpace(world);
        PredictionService prediction = new PredictionService(simulation);
        setup.accept(prediction);
        prediction.setPhysics((entity, base) -> ((Replayed) entity.get()).rules(world.now));

        int longest = horizons[horizons.length - 1];
        List<Scored> scored = new ArrayList<>();
        Map<MovementRecording.Track, Map<Long, Fresh>> seen = new HashMap<>();
        Map<String, Behaviour> behaviours = new LinkedHashMap<>();
        for (long tick = 1; tick <= recording.getTicks(); tick++) {
            world.now = tick;
            for (Replayed entity : replayed.values()) {
                entity.advance(tick);
            }
            entities.refresh();
            for (Map.Entry<MovementRecording.Track, Replayed> entry : replayed.entrySet()) {
                Replayed entity = entry.getValue();
                if (entity.current == null) {
                    continue;
                }
                Tracked<Replayed> tracked = trackers.get(entry.getKey()).get(entity);
                if (tracked == null) {
                    continue;
                }
                seen.computeIfAbsent(entry.getKey(), key -> new HashMap<>()).put(tick,
                        new Fresh(tracked.isFreshAgo(0), entity.current));
                scored.add(new Scored(entry.getKey(), tick, prediction.predict(tracked, longest)));
                behaviours.put(name(entry.getKey()), prediction.behaviour(tracked));
            }
        }

        List<ReplayReport.Horizon> results = new ArrayList<>();
        for (int horizon : horizons) {
            List<Double> errors = new ArrayList<>();
            List<Double> reliableErrors = new ArrayList<>();
            int checks = 0;
            int late = 0;
            for (Scored one : scored) {
                Map<Long, Fresh> track = seen.get(one.track);
                Fresh then = track == null ? null : track.get(one.tick + horizon);
                if (then == null || !then.fresh) {
                    continue;
                }
                MovementRecording.Sample truth = then.sample;
                Vec3 real = Vec3.of(truth.getX(), truth.getY(), truth.getZ());
                double error = one.prediction.positionAt(horizon).distanceTo(real);
                errors.add(error);
                if (one.prediction.isReliable()) {
                    reliableErrors.add(error);
                }
                Box box = MotionState.at(real).hitbox(truth.getWidth(), truth.getHeight());
                checks++;
                if (one.prediction.earliestPossible(box) > horizon) {
                    late++;
                }
            }
            results.add(summarise(horizon, errors, reliableErrors, checks, late));
        }
        return new ReplayReport(recording.getLabel(), results, behaviours);
    }

    private static ReplayReport.Horizon summarise(int horizon, List<Double> errors, List<Double> reliable, int checks,
                                                  int late) {
        if (errors.isEmpty()) {
            return new ReplayReport.Horizon(horizon, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    checks, late);
        }
        List<Double> sorted = new ArrayList<>(errors);
        sorted.sort(null);
        double total = 0d;
        for (double error : errors) {
            total += error;
        }
        double reliableTotal = 0d;
        for (double error : reliable) {
            reliableTotal += error;
        }
        double p95 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95d) - 1));
        return new ReplayReport.Horizon(horizon, errors.size(), total / errors.size(), p95,
                sorted.get(sorted.size() - 1), reliable.size() / (double) errors.size(),
                reliable.isEmpty() ? Double.NaN : reliableTotal / reliable.size(), checks, late);
    }

    private static String name(MovementRecording.Track track) {
        return track.getName() != null ? track.getName() : "#" + track.getId();
    }

    /** One prediction made during the replay, waiting to be scored. */
    private static final class Scored {
        final MovementRecording.Track track;
        final long tick;
        final Prediction prediction;

        Scored(MovementRecording.Track track, long tick, Prediction prediction) {
            this.track = track;
            this.tick = tick;
            this.prediction = prediction;
        }
    }

    /** What the replayed entity's tracker made of a tick: whether its position was news. */
    private static final class Fresh {
        final boolean fresh;
        final MovementRecording.Sample sample;

        Fresh(boolean fresh, MovementRecording.Sample sample) {
            this.fresh = fresh;
            this.sample = sample;
        }
    }

    /** A recorded entity, standing in for the game's: its sample for the tick being replayed. */
    static final class Replayed {
        final MovementRecording.Track track;
        MovementRecording.Sample current;
        private int next;

        Replayed(MovementRecording.Track track) {
            this.track = track;
        }

        void advance(long tick) {
            current = null;
            List<MovementRecording.Sample> samples = track.getSamples();
            while (next < samples.size() && samples.get(next).getTick() < tick) {
                next++;
            }
            if (next < samples.size() && samples.get(next).getTick() == tick) {
                current = samples.get(next);
            }
        }

        /** @return the rules recorded for it as of {@code tick}; null while it was not moving by them */
        PhysicsProfile rules(long tick) {
            PhysicsProfile profile = null;
            for (MovementRecording.Rules rules : track.getRules()) {
                if (rules.getTick() > tick) {
                    break;
                }
                profile = rules.getProfile();
            }
            return profile;
        }
    }

    private static final class Source implements EntitySource<Replayed> {
        private final Map<MovementRecording.Track, Replayed> replayed;

        Source(Map<MovementRecording.Track, Replayed> replayed) {
            this.replayed = replayed;
        }

        @Override
        public Iterable<Replayed> entities() {
            List<Replayed> present = new ArrayList<>();
            for (Replayed entity : replayed.values()) {
                if (entity.current != null) {
                    present.add(entity);
                }
            }
            return present;
        }

        @Override
        public Replayed self() {
            return null;
        }

        @Override
        public double x(Replayed entity) {
            return entity.current.getX();
        }

        @Override
        public double y(Replayed entity) {
            return entity.current.getY();
        }

        @Override
        public double z(Replayed entity) {
            return entity.current.getZ();
        }

        @Override
        public double width(Replayed entity) {
            return entity.current.getWidth();
        }

        @Override
        public double height(Replayed entity) {
            return entity.current.getHeight();
        }

        @Override
        public float yaw(Replayed entity) {
            return entity.current.getYaw();
        }

        @Override
        public long positionStamp(Replayed entity) {
            return entity.current.getStamp();
        }
    }

    /** The world as it was captured, at the tick being replayed. */
    private static final class World implements CollisionSpace {
        private final int size;
        private final Map<Long, List<MovementRecording.Blocks>> sections = new HashMap<>();
        long now;

        World(MovementRecording recording) {
            this.size = recording.getSectionSize();
            for (MovementRecording.Blocks blocks : recording.getBlocks()) {
                sections.computeIfAbsent(key(blocks.getSectionX(), blocks.getSectionY(), blocks.getSectionZ()),
                        key -> new ArrayList<>()).add(blocks);
            }
        }

        @Override
        public List<Box> boxesIn(Box region) {
            List<Box> found = new ArrayList<>();
            int minX = (int) Math.floor(region.getMinX() / size);
            int maxX = (int) Math.floor(region.getMaxX() / size);
            int minY = (int) Math.floor(region.getMinY() / size);
            int maxY = (int) Math.floor(region.getMaxY() / size);
            int minZ = (int) Math.floor(region.getMinZ() / size);
            int maxZ = (int) Math.floor(region.getMaxZ() / size);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        List<MovementRecording.Blocks> versions = sections.get(key(x, y, z));
                        if (versions == null) {
                            continue;
                        }
                        // The capture in force now; before the first, the first is the best there is.
                        MovementRecording.Blocks chosen = versions.get(0);
                        for (MovementRecording.Blocks version : versions) {
                            if (version.getTick() <= now) {
                                chosen = version;
                            }
                        }
                        for (Box box : chosen.getBoxes()) {
                            if (box.getMaxX() >= region.getMinX() && box.getMinX() <= region.getMaxX()
                                    && box.getMaxY() >= region.getMinY() && box.getMinY() <= region.getMaxY()
                                    && box.getMaxZ() >= region.getMinZ() && box.getMinZ() <= region.getMaxZ()) {
                                found.add(box);
                            }
                        }
                    }
                }
            }
            return found;
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
        }
    }

    private static final class Silent implements CoreLogger {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable thrown) {
        }

        @Override
        public void debug(String message) {
        }
    }
}
