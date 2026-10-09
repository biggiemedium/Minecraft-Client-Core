package dev.px.testkit;

import dev.px.core.control.ControlService;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.Event;
import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.flow.FlowHandle;
import dev.px.core.flow.FlowService;
import dev.px.core.math.Vec3;
import dev.px.core.memory.Memory;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.movement.rotation.RotationService;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.service.Service;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A world with no game in it: Core's services, a block world, a player and other
 * entities, and a tick runner, for testing flows and anything built on Core.
 *
 * <pre>{@code
 * Sandbox sandbox = Sandbox.create();
 * sandbox.world().floor(64, -20, 20);
 * SimPlayer me = sandbox.player(Vec3.of(0.5, 64, 0.5));
 * SimEntity zombie = sandbox.entity("zombie", Vec3.of(8.5, 64, 0.5), tick -> MovementInput.forward(90f));
 *
 * FlowHandle bot = sandbox.flows().start("bot", myFlow, 50);
 * int ticks = sandbox.runUntil(bot::isDone, 400);
 *
 * assertEquals(FlowHandle.State.DONE, bot.getState());
 * System.out.println(bot.view());                     // why it did what it did
 * }</pre>
 *
 * <h2>Its own services</h2>
 *
 * <p>Every sandbox builds its own controls, rotations, simulation, prediction,
 * entities, targeting, memory and flows, with no {@code Core} instance behind
 * them, so any number can run in one JVM without touching each other. Steps that
 * reach services through their {@link dev.px.core.flow.FlowContext} &mdash; not
 * {@code Core}'s statics &mdash; run here unchanged; the sandbox
 * {@linkplain FlowService#provide provides} its simulation, prediction, entities,
 * targeting, world and player to them.
 *
 * <h2>A tick</h2>
 *
 * <ol>
 *   <li>{@link TickEvent} {@link Stage#PRE}: the entity service reads the world,
 *       the controls and rotations open the tick, and the flows run.
 *   <li>The rotation and the keys are applied to the player, as an adapter would
 *       where the game reads its input.
 *   <li>The player moves by the real rules with them, and every entity by its
 *       script.
 *   <li>{@link TickEvent} {@link Stage#POST}, and the clock moves on.
 * </ol>
 *
 * <p>The clock is the sandbox's own: each tick moves it on by
 * {@linkplain #setTickMillis a set amount}, so a flow waiting
 * {@code Span.seconds(2)} finishes after the same number of ticks every run.
 */
public final class Sandbox {

    /**
     * Wall-clock milliseconds each tick moves the sandbox clock on by, by default:
     * the sandbox's choice, so seconds pass at a steady pace. Not a claim about any
     * game's tick rate; set your own.
     */
    public static final long DEFAULT_TICK_MILLIS = 50L;

    private final SandboxLogger logger = new SandboxLogger();
    private final EventBus bus;
    private final ControlService controls;
    private final RotationService rotations;
    private final SimulationService simulation;
    private final PredictionService prediction;
    private final EntityService entities;
    private final TargetService targets;
    private final Memory memory;
    private final FlowService flows;
    private final SimWorld world;
    private final PhysicsProfile profile;
    private final EntityTracker<SimEntity> tracker;
    private final List<SimEntity> others = new ArrayList<>();
    private final List<Service> started = new ArrayList<>();

    private SimPlayer player;
    private long nanos;
    private long tickMillis = DEFAULT_TICK_MILLIS;
    private long tick;

    private Sandbox(PhysicsProfile profile) {
        this.profile = profile;
        this.bus = new CoreEventBus(logger);
        this.world = new SimWorld(profile);
        this.controls = new ControlService(logger, bus);
        this.rotations = new RotationService(logger, bus);
        this.simulation = new SimulationService(logger, bus);
        simulation.setProfile(profile);
        simulation.setCollisionSpace(world);
        this.prediction = new PredictionService(simulation);
        this.entities = new EntityService(logger, bus);
        entities.setSource(new Source());
        this.tracker = entities.register(EntityTracker.of(SimEntity.class));
        this.targets = new TargetService(entities);
        this.memory = new Memory(bus, () -> nanos);
        this.flows = new FlowService(logger, bus, controls, rotations, memory, () -> nanos);
        flows.provide(SimulationService.class, simulation);
        flows.provide(PredictionService.class, prediction);
        flows.provide(EntityService.class, entities);
        flows.provide(TargetService.class, targets);
        flows.provide(SimWorld.class, world);
        flows.provide(Sandbox.class, this);
        for (Service service : new Service[] {controls, rotations, simulation, prediction, entities, targets,
                memory, flows}) {
            try {
                service.start();
            } catch (Exception thrown) {
                throw new IllegalStateException("the sandbox could not start " + service.getName(), thrown);
            }
            started.add(service);
        }
    }

    /** @return a sandbox moving by the default movement rules, {@link PhysicsProfile#vanilla()} */
    public static Sandbox create() {
        return create(PhysicsProfile.vanilla());
    }

    /** @return a sandbox whose simulation, player and entities move by {@code profile} */
    public static Sandbox create(PhysicsProfile profile) {
        return new Sandbox(Validate.notNull(profile, "profile"));
    }

    // ------------------------------------------------------------ the world

    public SimWorld world() {
        return world;
    }

    /**
     * Puts the player in the world, standing at {@code at}, and makes it the sink
     * for the controls and rotations. One player per sandbox.
     */
    public SimPlayer player(Vec3 at) {
        Validate.notNull(at, "at");
        Validate.check(player == null, "the sandbox already has a player");
        player = new SimPlayer(at, profile);
        controls.setMovementSink(player);
        controls.setClickSink(player);
        rotations.setSink(player);
        flows.provide(SimPlayer.class, player);
        return player;
    }

    /** @return the player, or null before {@link #player(Vec3)} */
    public SimPlayer getPlayer() {
        return player;
    }

    /** Adds someone else, standing at {@code at}, moved by {@code script}. Seen from the next tick. */
    public SimEntity entity(String name, Vec3 at, SimEntity.Script script) {
        Validate.notBlank(name, "name");
        Validate.notNull(at, "at");
        Validate.notNull(script, "script");
        SimEntity entity = new SimEntity(name, at, script, profile);
        others.add(entity);
        return entity;
    }

    public List<SimEntity> getEntities() {
        return Collections.unmodifiableList(others);
    }

    /** @return a selector over every entity in the sandbox, for targeting and danger */
    public TargetSelector<SimEntity> selector() {
        return TargetSelector.from(tracker).build();
    }

    /** @return the tracked view of {@code entity}, as targeting and prediction see it; null before it is seen */
    public Tracked<SimEntity> tracked(SimEntity entity) {
        return tracker.get(entity);
    }

    // ------------------------------------------------------------ running

    /** Runs one tick. */
    public void tick() {
        bus.post(new TickEvent(Stage.PRE));
        rotations.apply();
        controls.applyMovement();
        controls.applyClicks();
        if (player != null) {
            player.step(world);
        }
        for (SimEntity entity : others) {
            if (!entity.isRemoved()) {
                entity.step(world);
            }
        }
        bus.post(new TickEvent(Stage.POST));
        nanos += tickMillis * 1_000_000L;
        tick++;
    }

    public void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }

    /**
     * Ticks until {@code done} holds, checked after each tick.
     *
     * @return the ticks it took, or -1 if {@code limit} ran out first
     */
    public int runUntil(BooleanSupplier done, int limit) {
        Validate.notNull(done, "done");
        for (int i = 1; i <= limit; i++) {
            tick();
            if (done.getAsBoolean()) {
                return i;
            }
        }
        return -1;
    }

    /** Ticks until {@code flow} is over. @return the ticks it took, or -1 */
    public int runUntilDone(FlowHandle flow, int limit) {
        Validate.notNull(flow, "flow");
        return runUntil(flow::isDone, limit);
    }

    /** Posts {@code event} on the sandbox's bus, as the game would through the adapter. */
    public <E extends Event> E post(E event) {
        return bus.post(event);
    }

    /** The player leaves the world: every flow pauses. */
    public void leaveWorld() {
        bus.post(new WorldEvent(false, ""));
    }

    /** The player joins a world: every flow resumes, rewinding as it does. */
    public void joinWorld() {
        bus.post(new WorldEvent(true, ""));
    }

    /** Stops every service. The sandbox cannot be ticked after. */
    public void close() {
        for (int i = started.size() - 1; i >= 0; i--) {
            started.get(i).stop();
        }
        started.clear();
    }

    // ------------------------------------------------------------ clock

    /** How far each tick moves the clock on, in milliseconds. */
    public void setTickMillis(long millis) {
        Validate.check(millis >= 0, "tick millis must not be negative, got " + millis);
        this.tickMillis = millis;
    }

    /** @return ticks run */
    public long getTick() {
        return tick;
    }

    /** @return the sandbox clock, in nanoseconds */
    public long nanos() {
        return nanos;
    }

    // ------------------------------------------------------------ services

    public SandboxLogger logger() {
        return logger;
    }

    public EventBus bus() {
        return bus;
    }

    public ControlService controls() {
        return controls;
    }

    public RotationService rotations() {
        return rotations;
    }

    public SimulationService simulation() {
        return simulation;
    }

    public PredictionService prediction() {
        return prediction;
    }

    public EntityService entities() {
        return entities;
    }

    public TargetService targets() {
        return targets;
    }

    public Memory memory() {
        return memory;
    }

    public FlowService flows() {
        return flows;
    }

    /** The sandbox's entities as the game would hand them to an adapter: positions only. */
    private final class Source implements EntitySource<Object> {

        /** Where "you" are with no player: far off, out of everyone's way. */
        private final Object nobody = new Object();

        @Override
        public Iterable<Object> entities() {
            List<Object> visible = new ArrayList<>(others.size());
            for (SimEntity entity : others) {
                if (!entity.isRemoved()) {
                    visible.add(entity);
                }
            }
            return visible;
        }

        @Override
        public Object self() {
            return player != null ? player : nobody;
        }

        @Override
        public double x(Object entity) {
            return position(entity).getX();
        }

        @Override
        public double y(Object entity) {
            return position(entity).getY();
        }

        @Override
        public double z(Object entity) {
            return position(entity).getZ();
        }

        @Override
        public double width(Object entity) {
            return rules(entity).getHitboxWidth();
        }

        @Override
        public double height(Object entity) {
            return rules(entity).getHitboxHeight();
        }

        @Override
        public double eyeHeight(Object entity) {
            if (entity instanceof SimEntity) {
                return ((SimEntity) entity).getEyeHeight();
            }
            return entity instanceof SimPlayer ? ((SimPlayer) entity).getEyeHeight() : 0d;
        }

        @Override
        public float yaw(Object entity) {
            if (entity instanceof SimEntity) {
                return ((SimEntity) entity).getYaw();
            }
            return entity instanceof SimPlayer ? ((SimPlayer) entity).getRotation().getYaw() : 0f;
        }

        private Vec3 position(Object entity) {
            if (entity instanceof SimEntity) {
                return ((SimEntity) entity).getPosition();
            }
            if (entity instanceof SimPlayer) {
                return ((SimPlayer) entity).getPosition();
            }
            return Vec3.of(1.0E6, 0, 1.0E6);
        }

        private PhysicsProfile rules(Object entity) {
            if (entity instanceof SimEntity) {
                return ((SimEntity) entity).getRules();
            }
            return entity instanceof SimPlayer ? ((SimPlayer) entity).getRules() : profile;
        }
    }
}
