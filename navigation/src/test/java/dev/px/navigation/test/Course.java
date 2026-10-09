package dev.px.navigation.test;

import dev.px.core.control.ControlService;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.rotation.RotationMode;
import dev.px.core.movement.rotation.RotationService;
import dev.px.core.movement.rotation.RotationSink;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.navigation.Progress;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.RecordingControls;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.util.math.PhysicsProfile;
import dev.px.navigation.Navigator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A player in a world of hand-placed blocks, moved by the real rules with
 * whatever keys Core's controls hand it.
 *
 * <p>The "game" here is {@link Simulation} itself, so a route planned against it
 * is followed exactly unless a test pushes the player, changes the world under
 * them, or moves them by other rules on purpose &mdash; which is how each of the
 * navigator's reasons to plan again is provoked.
 */
final class Course {

    static final PhysicsProfile VANILLA = PhysicsProfile.vanilla();

    final GridCollisionSpace world = new GridCollisionSpace();
    /** The world, counting how often each block is asked about. */
    final CountingSpace counted = new CountingSpace(world);
    final SimulationService simulation;
    final ControlService controls;
    final RotationService rotations;
    final RecordingControls keys = new RecordingControls();
    final Head head = new Head();
    final List<MotionState> trail = new ArrayList<>();

    MotionState player;
    /** The rules the player really moves by; the simulation's unless a test says otherwise. */
    PhysicsProfile rules = VANILLA;
    /** Added to the player's position every tick: a current the simulation knows nothing about. */
    Vec3 push = Vec3.ZERO;
    int ticks;

    Course(Vec3 start) {
        RecordingLogger logger = new RecordingLogger();
        simulation = new SimulationService(logger, new CoreEventBus(logger));
        simulation.setProfile(VANILLA);
        simulation.setCollisionSpace(counted);
        controls = new ControlService(logger, new CoreEventBus(logger));
        controls.setMovementSink(keys);
        controls.setClickSink(keys);
        rotations = new RotationService(logger, new CoreEventBus(logger));
        rotations.setSink(head);
        player = MotionState.at(start);
    }

    /** A flat floor whose top is at y = 64, from -size to size each way. */
    static Course flat(Vec3 start, int size) {
        Course course = new Course(start);
        course.world.floor(64, -size, size);
        return course;
    }

    /** One game tick: open the tick, let the navigator claim, apply, move. */
    Progress tick(Navigator navigator) {
        rotations.beginTick();
        controls.beginTick();
        Progress progress = navigator.tick(player);
        rotations.apply();
        controls.applyMovement();
        MovementInput held = controls.getMovement();
        if (held == null) {
            held = MovementInput.none(head.rotation.getYaw());
        }
        player = Simulation.step(rules, player, held, world);
        if (push != Vec3.ZERO) {
            player = player.withPosition(player.getPosition().add(push));
        }
        trail.add(player);
        ticks++;
        return progress;
    }

    /** Ticks until the navigator arrives, fails, or {@code limit} runs out. */
    Progress run(Navigator navigator, int limit) {
        Progress progress = null;
        for (int i = 0; i < limit; i++) {
            progress = tick(navigator);
            if (progress.isDone()) {
                return progress;
            }
        }
        return progress;
    }

    /** Plays a route's inputs through the world from its start: what the game would do with them. */
    static List<MotionState> replay(dev.px.core.navigation.Route route, CollisionSpace world) {
        List<MotionState> states = new ArrayList<>();
        MotionState state = route.getStart();
        for (MovementInput input : route.getInputs()) {
            state = Simulation.step(VANILLA, state, input, world);
            states.add(state);
        }
        return states;
    }

    /** A head that turns where it is told, as a CLIENT-mode sink does. */
    static final class Head implements RotationSink {

        Vec2 rotation = Vec2.ZERO;
        final List<Vec2> applied = new ArrayList<>();

        @Override
        public Vec2 getRotation() {
            return rotation;
        }

        @Override
        public void apply(Vec2 rotation, RotationMode mode) {
            this.rotation = rotation;
            applied.add(rotation);
        }
    }

    /** The world, remembering every block it was asked about. */
    static final class CountingSpace implements CollisionSpace {

        private final CollisionSpace world;
        final Set<Vec3i> asked = new HashSet<>();
        int queries;
        int repeats;

        CountingSpace(CollisionSpace world) {
            this.world = world;
        }

        @Override
        public List<Box> boxesIn(Box region) {
            queries++;
            Vec3i cell = Vec3i.floorOf(region.getMinX(), region.getMinY(), region.getMinZ());
            boolean oneBlock = Math.floor(region.getMaxX()) == cell.getX()
                    && Math.floor(region.getMaxY()) == cell.getY()
                    && Math.floor(region.getMaxZ()) == cell.getZ();
            if (oneBlock && !asked.add(cell)) {
                repeats++;
            }
            return world.boxesIn(region);
        }

        @Override
        public double slipperinessAt(Vec3 position) {
            return world.slipperinessAt(position);
        }

        void reset() {
            asked.clear();
            queries = 0;
            repeats = 0;
        }
    }
}
