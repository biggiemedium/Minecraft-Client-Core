package dev.px.combat.test;

import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.explosion.rule.Falloff;
import dev.px.combat.explosion.rule.Mitigation;
import dev.px.combat.explosion.rule.SampleGrid;
import dev.px.combat.explosion.state.StateCapture;
import dev.px.combat.explosion.state.StateMitigation;
import dev.px.combat.explosion.state.TargetState;
import dev.px.combat.monitor.DamageMonitor;
import dev.px.combat.monitor.DamageReport;
import dev.px.combat.monitor.DamageSample;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.vector.BlockSnapshot;
import dev.px.combat.vector.TestVector;
import dev.px.combat.vector.VectorSet;
import dev.px.combat.vector.capture.VectorRecorder;
import dev.px.combat.vector.io.VectorJson;
import dev.px.combat.vector.replay.ReplayResult;
import dev.px.combat.vector.replay.VectorReplay;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test vectors, end to end: recorded while "playing", saved to JSON and read
 * back, then replayed against version profiles with no game at all.
 *
 * <p>The game's real rules are the test's own. A profile that matches them must
 * reproduce every vector; one wrong in a single rule must fail, and the failures
 * must point at that rule.
 */
public final class VectorTests {

    private static final Explosive CRYSTAL = Explosive.of("end crystal", 6);

    /** The test's version formula, as a client would write it. */
    private static final Falloff WIKI = Falloff.of(
            power -> 2 * power,
            (distance, exposure, power) -> {
                double impact = (1 - distance / (2 * power)) * exposure;
                return 7 * power * (impact * impact + impact) + 1;
            });

    /** The test game's armour, written once against captured state. */
    private static final StateMitigation ARMOUR = (damage, state) -> damage * (1 - state.get("armor", 0) / 25);

    private static final Exposure GRID = Exposure.sampled(SampleGrid.uniform(3, 5, 3));

    private VectorTests() {
    }

    public static void run() {
        Checks.section("Test vectors");

        targetState();
        snapshots();
        recording();
        json();
        replay();
        pops();
    }

    // -------------------------------------------------------------- pieces

    private static void targetState() {
        TargetState state = TargetState.builder().put("armor", 20).put("blocking", true).put("note", "x").build();
        Checks.check("a state holds numbers, flags and text",
                state.get("armor") == 20d && state.flag("blocking") && "x".equals(state.text("note")));
        Checks.check("and answers NaN, false or null for what it lacks",
                Double.isNaN(state.get("missing")) && !state.flag("missing") && state.text("missing") == null
                        && state.get("missing", 3) == 3d);
        Checks.check("states are equal by value", state.equals(TargetState.builder()
                .put("armor", 20).put("blocking", true).put("note", "x").build()));
        Checks.checkThrows("and refuse anything else", IllegalArgumentException.class,
                () -> TargetState.builder().putAny("list", new ArrayList<>()));

        World world = new World();
        Fighter fighter = world.add(new Fighter(0, 0, 0));
        fighter.armor = 10;
        world.service.refresh();
        Tracked<Fighter> live = world.tracker.get(fighter);
        Mitigation<Fighter> fromState = Mitigation.fromState(Fighter::state, ARMOUR);
        Mitigation<TargetState> ofState = Mitigation.ofState(ARMOUR);
        World recorded = new World();
        Tracked<TargetState> replayed = recorded.stateTarget(fighter.state());
        Checks.check("one mitigation, written against state, gives the same answer live and recorded",
                fromState.apply(40, live) == ofState.apply(40, replayed) && fromState.apply(40, live) == 24d);
    }

    private static void snapshots() {
        Blocks blocks = new Blocks();
        BlockShape slab = BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1));
        blocks.set(-5, -64, 3, BlockShape.FULL);
        blocks.set(-4, -64, 3, BlockShape.FULL);
        blocks.set(-3, -64, 3, slab);
        blocks.set(40, 0, 0, BlockShape.FULL);
        BlockSnapshot snapshot = BlockSnapshot.capture(blocks, Box.of(-6, -65, 2, -2, -63, 4));
        Checks.checkEquals("a snapshot keeps every non-empty cell in its region", 3, snapshot.size());
        Checks.checkEquals("each distinct shape once", 2, snapshot.getPalette().size());
        Checks.check("and answers as the world did, even at negative coordinates",
                snapshot.shapeAt(-5, -64, 3).equals(BlockShape.FULL) && snapshot.shapeAt(-3, -64, 3).equals(slab));
        Checks.check("with nothing outside its region", snapshot.shapeAt(40, 0, 0).isEmpty());
        Checks.check("its cells rebuild it exactly",
                BlockSnapshot.of(snapshot.getPalette(), snapshot.getCells()).equals(snapshot));
    }

    // ------------------------------------------------------------ recording

    private static void recording() {
        Session session = new Session();
        session.play(12);
        List<TestVector> vectors = session.recorder.getVectors();
        Checks.checkEquals("every sample the monitor keeps becomes a vector",
                (float) session.monitor.getSamples().size(), (float) vectors.size());
        TestVector armoured = session.vectorFor(session.armoured);
        Checks.check("with the state the mitigation reads", armoured != null && armoured.getState().get("armor") == 15d);
        TestVector covered = session.vectorFor(session.covered);
        Checks.check("the blocks between explosion and target",
                covered != null && covered.getBlocks().size() > 0 && covered.getRecorded().getExposure() < 1d
                        && covered.getRecorded().getExposure() > 0d);
        Checks.check("and the target as it stood",
                Checks.eq((float) covered.getWidth(), 0.6f) && Checks.eq((float) covered.getHeight(), 1.8f)
                        && covered.getPosition().getX() == session.covered.x);

        Session hurt = new Session();
        hurt.enemy.recentlyHurt = true;
        hurt.play(1);
        Checks.check("a sample the monitor throws away is not recorded",
                hurt.recorder.getVectors().stream().noneMatch(v -> v.getPosition().getZ() == hurt.enemy.z));

        Session merged = new Session();
        merged.heal();
        Vec3 near = Vec3.of(1, 1, 0.3);
        Vec3 far = Vec3.of(6, 1, 0.3);
        merged.monitor.exploded(far, CRYSTAL);
        merged.monitor.exploded(near, CRYSTAL);
        merged.landStrongest(near, far);
        merged.settle();
        boolean strongest = !merged.recorder.getVectors().isEmpty();
        for (TestVector vector : merged.recorder.getVectors()) {
            Tracked<Fighter> target = merged.trackedAt(vector.getPosition());
            Vec3 other = vector.getOrigin().equals(near) ? far : near;
            strongest &= merged.truth.damage(vector.getOrigin(), CRYSTAL, target, merged.blocks)
                    >= merged.truth.damage(other, CRYSTAL, target, merged.blocks);
        }
        Checks.check("two explosions in one tick record, for each target, the one that hit it hardest", strongest);

        Session small = new Session(4);
        small.play(5);
        Checks.checkEquals("a recorder keeps only its capacity, newest winning", 4, small.recorder.size());
    }

    private static void json() {
        Session session = new Session();
        session.play(6);
        VectorSet set = session.recorder.toSet("test game", Collections.singletonMap("difficulty", "hard"));
        try {
            StringWriter out = new StringWriter();
            VectorJson.write(set, out);
            VectorSet read = VectorJson.read(new StringReader(out.toString()));
            Checks.check("a set reads back from JSON equal to the one written", read.equals(set) && read.size() > 0);
            Checks.check("as readable text", out.toString().contains("\"format\": \"px-explosion-vectors\"")
                    && out.toString().contains("\"armor\""));
        } catch (IOException e) {
            Checks.check("writing and reading vectors (" + e + ")", false);
        }
        Checks.check("a file of another format is refused", refused("{\"format\":\"something-else\",\"version\":1}"));
        Checks.check("as is one from a newer version",
                refused("{\"format\":\"px-explosion-vectors\",\"version\":99,\"label\":\"x\",\"vectors\":[]}"));
    }

    private static boolean refused(String json) {
        try {
            VectorJson.read(new StringReader(json));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    // --------------------------------------------------------------- replay

    private static void replay() {
        Session session = new Session();
        session.play(15);
        VectorSet set;
        try {
            StringWriter out = new StringWriter();
            VectorJson.write(session.recorder.toSet("test game"), out);
            set = VectorJson.read(new StringReader(out.toString()));       // replay what was saved, not what is in memory
        } catch (IOException e) {
            Checks.check("saving the session (" + e + ")", false);
            return;
        }

        ReplayResult right = VectorReplay.against(profile(WIKI, ARMOUR, GRID)).run(set);
        Checks.check("the right profile reproduces every vector, with no game (" + right.getFailedCount() + " failed)",
                right.isPassed() && right.getPassedCount() == set.size());
        Checks.check("from the recorded blocks alone",
                set.getVectors().stream().anyMatch(v -> v.getBlocks().size() > 0));

        ReplayResult falloff = VectorReplay.against(profile(scaled(0.8), ARMOUR, GRID)).run(set);
        Checks.check("a wrong formula fails, and is blamed on the falloff (" + falloff.getSuspect() + ")",
                !falloff.isPassed() && falloff.getSuspect() == DamageReport.Suspect.FALLOFF);

        StateMitigation weakArmour = (damage, state) -> damage * (1 - state.get("armor", 0) / 40);
        ReplayResult armour = VectorReplay.against(profile(WIKI, weakArmour, GRID)).run(set);
        Checks.check("wrong armour fails only armoured vectors, and is blamed on mitigation (" + armour.getSuspect() + ")",
                armour.getSuspect() == DamageReport.Suspect.MITIGATION
                        && armour.getFailed(DamageSample.Bucket.OPEN) == 0 && armour.getFailed(DamageSample.Bucket.ARMOURED) > 0);

        ReplayResult exposure = VectorReplay.against(profile(WIKI, ARMOUR, Exposure.sampled(SampleGrid.uniform(1, 2, 1))))
                .run(set);
        Checks.check("a wrong sampling fails only covered vectors, and is blamed on exposure (" + exposure.getSuspect() + ")",
                exposure.getSuspect() == DamageReport.Suspect.EXPOSURE
                        && exposure.getFailed(DamageSample.Bucket.OPEN) == 0);

        ReplayResult loose = VectorReplay.against(profile(scaled(0.999), ARMOUR, GRID)).tolerance(1).run(set);
        Checks.check("the tolerance is yours to set", loose.isPassed());
        Checks.check("and a failure says what it predicted and what happened",
                falloff.getFailures().get(0).getError() > 0d && falloff.toString().contains("FAIL"));
    }

    private static void pops() {
        TestVector popped = TestVector.builder()
                .explosive(CRYSTAL).origin(Vec3.of(0, 1, 0))
                .target(Vec3.of(2, 1, 0), 0.6, 1.8, 1.53)
                .state(TargetState.empty())
                .observed(30, true)
                .build();
        ReplayResult enough = VectorReplay.against(profile(WIKI, ARMOUR, Exposure.FULL)).run(Arrays.asList(popped));
        Checks.check("a pop passes when the profile predicts at least what the target had", enough.isPassed());
        ReplayResult short_ = VectorReplay.against(profile(scaled(0.1), ARMOUR, Exposure.FULL)).run(Arrays.asList(popped));
        Checks.check("and fails when it says the target survives",
                !short_.isPassed() && short_.getFailedPops() == 1 && short_.getSuspect() == DamageReport.Suspect.UNKNOWN);
        Checks.checkThrows("a vector without what was observed is refused", IllegalStateException.class,
                () -> TestVector.builder().explosive(CRYSTAL).origin(Vec3.ZERO).target(Vec3.ZERO, 1, 1, 0).build());
    }

    // ------------------------------------------------------------- helpers

    private static Falloff scaled(double scale) {
        return Falloff.of(WIKI::range, (d, e, p) -> WIKI.damage(d, e, p) * scale);
    }

    /** A version profile for replay: mitigation against recorded state. */
    private static ExplosionModel<TargetState> profile(Falloff falloff, StateMitigation armour, Exposure exposure) {
        return ExplosionModel.<TargetState>builder()
                .measureFrom(Tracked::getPosition)
                .exposure(exposure)
                .falloff(falloff)
                .mitigation(Mitigation.ofState(armour))
                .build();
    }

    // ------------------------------------------------- the test's "game"

    /** Fighters among some blocks, the game's real rules, a monitor and a recorder. */
    private static final class Session {
        final World world = new World();
        final Blocks blocks = new Blocks();
        final Fighter self = world.add(new Fighter(0, 1, 0));
        final Fighter enemy = world.add(new Fighter(0, 1, 4));
        final Fighter armoured = world.add(new Fighter(4, 1, 0));
        final Fighter covered = world.add(new Fighter(-4, 1, 0));
        final ExplosionModel<Fighter> truth;
        final VectorRecorder<Fighter> recorder;
        final DamageMonitor<Fighter> monitor;

        Session() {
            this(VectorRecorder.DEFAULT_CAPACITY);
        }

        Session(int capacity) {
            armoured.armor = 15;
            BlockShape slab = BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1));
            for (int z = -1; z < 2; z++) {
                blocks.set(-2, 1, z, slab);                      // a half-height wall: the covered fighter is partly seen
            }
            world.self = self;
            world.service.refresh();
            truth = ExplosionModel.<Fighter>builder()
                    .measureFrom(Tracked::getPosition)
                    .exposure(GRID)
                    .falloff(WIKI)
                    .mitigation(Mitigation.fromState(Fighter::state, ARMOUR))
                    .build();
            recorder = VectorRecorder.<Fighter>builder()
                    .state((StateCapture<Fighter>) Fighter::state)
                    .blocks(blocks)
                    .capacity(capacity)
                    .build();
            monitor = DamageMonitor.<Fighter>builder()
                    .model(truth)
                    .blocks(blocks)
                    .vitals(new Vitals<Fighter>() {
                        @Override
                        public double pool(Fighter fighter) {
                            return fighter.health + fighter.absorption;
                        }

                        @Override
                        public boolean isRecentlyHurt(Fighter fighter) {
                            return fighter.recentlyHurt;
                        }
                    })
                    .targets(world.service, world.tracker)
                    .recorder(recorder)
                    .build();
        }

        void play(int rounds) {
            for (int round = 0; round < rounds; round++) {
                heal();
                Vec3 origin = Vec3.of(0.5 + (round % 5) * 0.7, 1, 0.3 + (round % 3) * 0.5);
                monitor.exploded(origin, CRYSTAL);
                landStrongest(origin);
                settle();
            }
        }

        void heal() {
            for (Fighter fighter : world.fighters) {
                fighter.health = 200;
                fighter.absorption = 16;
            }
        }

        /** What the game really does to everyone in range: the strongest explosion of the tick. */
        void landStrongest(Vec3... origins) {
            for (Fighter fighter : world.fighters) {
                Tracked<Fighter> tracked = fighter == world.self ? world.service.getSelf() : world.tracker.get(fighter);
                double strongest = 0;
                for (Vec3 origin : origins) {
                    strongest = Math.max(strongest, truth.damage(origin, CRYSTAL, tracked, blocks));
                }
                double fromAbsorption = Math.min(fighter.absorption, strongest);
                fighter.absorption -= fromAbsorption;
                fighter.health -= strongest - fromAbsorption;
            }
        }

        void settle() {
            for (int i = 0; i < DamageMonitor.DEFAULT_SETTLE_TICKS; i++) {
                monitor.tick();
            }
        }

        Tracked<Fighter> trackedAt(Vec3 position) {
            for (Fighter fighter : world.fighters) {
                if (fighter.x == position.getX() && fighter.z == position.getZ()) {
                    return fighter == world.self ? world.service.getSelf() : world.tracker.get(fighter);
                }
            }
            throw new IllegalStateException("nobody at " + position);
        }

        TestVector vectorFor(Fighter fighter) {
            for (TestVector vector : recorder.getVectors()) {
                if (vector.getPosition().getX() == fighter.x && vector.getPosition().getZ() == fighter.z) {
                    return vector;
                }
            }
            return null;
        }
    }

    private static final class Fighter {
        final double x;
        final double y;
        final double z;
        double health = 200;
        double absorption = 16;
        double armor;
        boolean recentlyHurt;

        Fighter(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        TargetState state() {
            return TargetState.builder().put("armor", armor).build();
        }
    }

    private static final class Blocks implements BlockView {
        private final Map<String, BlockShape> shapes = new HashMap<>();

        void set(int x, int y, int z, BlockShape shape) {
            shapes.put(x + "," + y + "," + z, shape);
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            BlockShape shape = shapes.get(x + "," + y + "," + z);
            return shape != null ? shape : BlockShape.EMPTY;
        }
    }

    private static final class World implements EntitySource<Object> {
        final List<Fighter> fighters = new ArrayList<>();
        final EntityService service;
        final EntityTracker<Fighter> tracker;
        final EntityTracker<TargetState> states;
        Fighter self;
        TargetState state;

        World() {
            RecordingLogger logger = new RecordingLogger();
            service = new EntityService(logger, new CoreEventBus(logger));
            tracker = service.register(EntityTracker.of(Fighter.class));
            states = service.register(EntityTracker.of(TargetState.class));
            service.setSource(this);
        }

        Fighter add(Fighter fighter) {
            fighters.add(fighter);
            return fighter;
        }

        /** A recorded target: a bare state, standing at the origin. */
        Tracked<TargetState> stateTarget(TargetState recorded) {
            state = recorded;
            service.refresh();
            return states.get(recorded);
        }

        @Override
        public Iterable<Object> entities() {
            List<Object> all = new ArrayList<Object>(fighters);
            if (state != null) {
                all.add(state);
            }
            return all;
        }

        @Override
        public Object self() {
            return self;
        }

        @Override
        public double x(Object entity) {
            return entity instanceof Fighter ? ((Fighter) entity).x : 0;
        }

        @Override
        public double y(Object entity) {
            return entity instanceof Fighter ? ((Fighter) entity).y : 0;
        }

        @Override
        public double z(Object entity) {
            return entity instanceof Fighter ? ((Fighter) entity).z : 0;
        }

        @Override
        public double width(Object entity) {
            return 0.6;
        }

        @Override
        public double height(Object entity) {
            return 1.8;
        }
    }
}
