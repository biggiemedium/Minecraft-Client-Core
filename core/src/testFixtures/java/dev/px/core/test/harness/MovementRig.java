package dev.px.core.test.harness;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * A world of moving bodies behind Core's own entity service, as a client sees
 * other players: positions only, as the server sends them.
 *
 * <p>Each body moves by real rules with keys from a script, or by a mover of its
 * own &mdash; a cheat that sets its velocity, a snap into a hole. The "server"
 * sends its position every {@code every} ticks, rounded to a precision if asked,
 * with or without a stamp. The prediction service sees only that.
 */
public final class MovementRig {

    public static final PhysicsProfile VANILLA = PhysicsProfile.vanilla();

    /** Keys for each tick. */
    @FunctionalInterface
    public interface Script {
        MovementInput at(int tick);
    }

    /** One tick of movement the rules do not produce. */
    @FunctionalInterface
    public interface Mover {
        MotionState next(Body body, MotionState state, int tick, CollisionSpace world);
    }

    /** Someone moving: by the rules with a script, or by a mover. */
    public static final class Body {
        public MotionState state;
        public final Script script;
        /** The rules it really moves by. */
        public PhysicsProfile rules = VANILLA;
        /** How the server rounds its position; 0 for exactly. */
        public double precision;
        /** The server sends its position every this many ticks. */
        public int every = 1;
        /** Whether the client's source gives a stamp with each position the server sends. */
        public boolean stamped;
        public Mover mover;
        public float yaw;
        public int tick;
        Vec3 sent;
        long stamp;

        Body(Vec3 at, Script script) {
            this.state = MotionState.at(at);
            this.script = script;
            this.sent = at;
        }

        public Body rules(PhysicsProfile rules) {
            this.rules = rules;
            return this;
        }

        public Body every(int ticks) {
            this.every = ticks;
            return this;
        }

        public Body stamped() {
            this.stamped = true;
            return this;
        }

        public Body precision(double blocks) {
            this.precision = blocks;
            return this;
        }

        public Body moving(Mover mover) {
            this.mover = mover;
            return this;
        }

        /** @return the coordinate as the client receives it: rounded to the protocol's precision, if any */
        public double seen(double value) {
            return precision > 0d ? Math.round(value / precision) * precision : value;
        }
    }

    public final CollisionSpace world;
    public final EntityService entities;
    public final EntityTracker<Body> bodies;
    public final SimulationService simulation;
    public final PredictionService prediction;
    public final List<Body> all = new ArrayList<>();
    public final List<Body> hidden = new ArrayList<>();
    private final Object me = new Object();
    /** You, when given a body: otherwise you stand far off, out of everyone's way. */
    private Body self;

    public MovementRig(CollisionSpace world) {
        this.world = world;
        RecordingLogger logger = new RecordingLogger();
        entities = new EntityService(logger, new CoreEventBus(logger));
        entities.setSource(new Source());
        bodies = entities.register(EntityTracker.of(Body.class));
        simulation = new SimulationService(logger, new CoreEventBus(logger));
        simulation.setProfile(VANILLA);
        simulation.setCollisionSpace(world);
        prediction = new PredictionService(simulation);
    }

    /** Gives you a body of your own, moving by {@code script} like anyone else's, eyes 1.62 up. */
    public Body self(Vec3 at, Script script) {
        self = new Body(at, script);
        return self;
    }

    public Body add(Vec3 at, Script script) {
        Body body = new Body(at, script);
        all.add(body);
        return body;
    }

    /** Every body takes a tick; the server sends whoever is due; then the client looks. */
    public void tick() {
        List<Body> moving = new ArrayList<>(all);
        if (self != null) {
            moving.add(self);
        }
        for (Body body : moving) {
            MovementInput input = body.script.at(body.tick);
            body.yaw = input.getYaw();
            body.state = body.mover != null
                    ? body.mover.next(body, body.state, body.tick, world)
                    : Simulation.step(body.rules, body.state, input, world);
            body.tick++;
            if (body.tick % body.every == 0) {
                body.sent = body.state.getPosition();
                body.stamp++;
            }
        }
        entities.refresh();
    }

    public Tracked<Body> tracked(Body body) {
        return bodies.get(body);
    }

    private final class Source implements EntitySource<Object> {
        @Override
        public Iterable<Object> entities() {
            List<Object> visible = new ArrayList<Object>(all);
            visible.removeAll(hidden);
            return visible;
        }

        @Override
        public Object self() {
            return self != null ? self : me;
        }

        @Override
        public double eyeHeight(Object entity) {
            return entity == me ? 0d : 1.62d;
        }

        @Override
        public double x(Object entity) {
            return entity == me ? 1000d : ((Body) entity).seen(((Body) entity).sent.getX());
        }

        @Override
        public double y(Object entity) {
            return entity == me ? 0d : ((Body) entity).seen(((Body) entity).sent.getY());
        }

        @Override
        public double z(Object entity) {
            return entity == me ? 1000d : ((Body) entity).seen(((Body) entity).sent.getZ());
        }

        @Override
        public double width(Object entity) {
            return 0.6d;
        }

        @Override
        public double height(Object entity) {
            return 1.8d;
        }

        @Override
        public float yaw(Object entity) {
            return entity == me ? 0f : ((Body) entity).yaw;
        }

        @Override
        public long positionStamp(Object entity) {
            return entity == me || !((Body) entity).stamped ? -1L : ((Body) entity).stamp;
        }
    }
}
