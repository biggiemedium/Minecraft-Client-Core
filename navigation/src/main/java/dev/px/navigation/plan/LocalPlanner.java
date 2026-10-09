package dev.px.navigation.plan;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.PathProvider;
import dev.px.core.navigation.Route;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;
import dev.px.navigation.danger.Danger;
import dev.px.navigation.danger.DangerField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Plans the exact keys to press, tick by tick, to get somewhere close by.
 *
 * <p>A block pathfinder answers "which blocks"; this answers "which keys, and
 * when". It searches over where the player is, how fast they are going and
 * whether they are on the ground, and every move it considers is played through
 * Core's {@link Simulation} against the world's collision boxes. So a route it
 * returns is one the game will actually do: the sprint-jump that clears the gap,
 * the run-up that makes it, the edge it would have slid off, the 1&times;1 hole it
 * lines up with before stepping in.
 *
 * <pre>{@code
 * LocalPlanner planner = LocalPlanner.builder()
 *         .simulation(Core.simulation())                 // the rules and the world
 *         .range(24)                                     // blocks; further is a long-range provider's job
 *         .maxDrop(() -> settings.safeFall.get())        // your rule: how far is safe to fall
 *         .build();
 *
 * Route route = planner.plan(Goal.block(x, y, z), playerState);
 * if (route == null) {
 *     log(planner.getLastStats().getReason());
 * }
 * }</pre>
 *
 * <h2>How it searches</h2>
 *
 * <p>A* over movement states. Each step of the search holds one {@link Gait}
 * at one heading for {@linkplain Builder#moveTicks a few ticks} &mdash; walking,
 * sprinting, sprint-jumping at eight headings, plus straight at the goal &mdash;
 * and simulates every tick of it. The cost is ticks, plus whatever a
 * {@link Danger} adds. The estimate is the goal's {@link dev.px.core.navigation.Gap}
 * over the fastest the rules let the player move across, up and down, which never
 * overestimates.
 *
 * <p>By default the search leans on that estimate twice as hard as on the
 * cost so far ({@linkplain Builder#greed greed} 2). On open ground that finds
 * the same route as a strict search after a few dozen states instead of
 * thousands; the price is that a route may be up to twice as slow as the
 * quickest, which in practice it is not. Set greed to 1 for the quickest the moves
 * allow, at many times the work.
 *
 * <p>States closer than {@linkplain Builder#resolution the resolution} count as
 * one &mdash; position, horizontal speed, vertical velocity and footing, but not
 * the direction of travel &mdash; which is what keeps the search finite. A finer
 * resolution finds tighter routes and costs more.
 *
 * <p><b>Cost.</b> Each state expanded simulates every gait at every heading for
 * the move's ticks: with the defaults, 27 moves of 6 ticks. A plan across open
 * ground expands a few dozen states and takes about a millisecond; one that has to
 * find its way round a wall between the player and the goal fills the space behind
 * it first, and can expand a thousand or more. The {@linkplain Builder#maxStates
 * state budget} caps the worst case.
 *
 * <h2>Limits, all yours to set</h2>
 *
 * <ul>
 *   <li><b>Range</b> and a <b>tick budget</b>: the planner is precise and short
 *       range. Beyond them a long-range provider takes over, and the navigator
 *       hands the local sections back to this.
 *   <li><b>A state budget</b>: how much work one plan may do. When it runs out
 *       the planner returns the route that got closest, marked incomplete.
 *   <li><b>A drop limit</b> and <b>a rule</b> for refusing states (lava, the void,
 *       anywhere you name): what is safe is a fact about the game, so the
 *       planner falls any distance until you say otherwise.
 * </ul>
 *
 * <h2>What it does not do</h2>
 *
 * <p>It only moves: no breaking through or bridging. It plans what
 * {@link Simulation} models, so not water, ladders or elytra, and not the sneak
 * clamp at edges. It plans with the world as it is when asked; the
 * {@linkplain dev.px.navigation.Navigator navigator} notices when the world
 * or the player stops matching and plans again.
 *
 * <p>Game thread only: it reads the world through the adapter's
 * {@link dev.px.core.movement.simulation.CollisionSpace}, each block once per plan.
 */
public final class LocalPlanner implements PathProvider {

    /** Blocks from the start a plan may reach, by default. A tuning knob, not a game value. */
    public static final int DEFAULT_RANGE = 24;

    /** States one plan may expand, by default. */
    public static final int DEFAULT_MAX_STATES = 2000;

    /** Ticks a route may take, by default. */
    public static final int DEFAULT_MAX_TICKS = 200;

    /** Evenly spaced headings tried, by default. */
    public static final int DEFAULT_HEADINGS = 8;

    /** Ticks each move holds its keys, by default. */
    public static final int DEFAULT_MOVE_TICKS = 6;

    /** Blocks between positions that count as different, by default. */
    public static final double DEFAULT_POSITION_RESOLUTION = 0.25d;

    /** Blocks a tick between speeds that count as different, by default. */
    public static final double DEFAULT_VELOCITY_RESOLUTION = 0.25d;

    /** How far the search leans towards the goal over the quickest route, by default. */
    public static final double DEFAULT_GREED = 2d;

    /**
     * Ticks closer to the goal, at least, a route that stops short must get.
     * Less is shuffling on the spot, and following it would only plan again from
     * where the plan began.
     */
    private static final double MIN_PARTIAL_GAIN = 1d;

    private final SimulationService simulation;
    private final IntSupplier range;
    private final IntSupplier maxStates;
    private final IntSupplier maxTicks;
    private final float[] headings;
    private final int moveTicks;
    private final List<Gait> gaits;
    private final BooleanSupplier canSprint;
    private final DoubleSupplier maxDrop;
    private final Predicate<MotionState> allowed;
    private final Danger danger;
    private final double positionResolution;
    private final double velocityResolution;
    private final double greed;
    private final double topSpeed;

    private PhysicsProfile paceProfile;
    private Pace pace;
    private PlanStats lastStats = PlanStats.none();

    private LocalPlanner(Builder builder) {
        this.simulation = builder.simulation;
        this.range = builder.range;
        this.maxStates = builder.maxStates;
        this.maxTicks = builder.maxTicks;
        this.headings = new float[builder.headings];
        for (int i = 0; i < builder.headings; i++) {
            headings[i] = (float) (i * 360d / builder.headings - 180d);
        }
        this.moveTicks = builder.moveTicks;
        this.gaits = Collections.unmodifiableList(new ArrayList<>(builder.gaits));
        this.canSprint = builder.canSprint;
        this.maxDrop = builder.maxDrop;
        this.allowed = builder.allowed;
        this.danger = builder.danger;
        this.positionResolution = builder.positionResolution;
        this.velocityResolution = builder.velocityResolution;
        this.greed = builder.greed;
        this.topSpeed = builder.topSpeed;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return what the last plan found, and why it threw away what it did */
    public PlanStats getLastStats() {
        return lastStats;
    }

    /** @return the gaits tried at every heading */
    public List<Gait> getGaits() {
        return gaits;
    }

    /** @return the furthest a plan may reach from where it starts, in blocks, as of now */
    public int getRange() {
        return range.getAsInt();
    }

    /**
     * Plans the exact keys from {@code from} to {@code goal}.
     *
     * @return a precise route: complete if it reaches the goal, partial if the
     *         search ran out of range, ticks or states first and this is as close
     *         as it got. Null when no move got any closer; {@link #getLastStats()}
     *         says why
     */
    @Override
    public Route plan(Goal goal, MotionState from) {
        Validate.notNull(goal, "goal");
        Validate.notNull(from, "from");
        long started = System.nanoTime();
        if (goal.isMet(from.getPosition())) {
            lastStats = new PlanStats(PlanStats.Outcome.ALREADY_THERE, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0,
                    (System.nanoTime() - started) / 1000L);
            return Route.precise(goal, from, Collections.<MovementInput>emptyList(),
                    Collections.<MotionState>emptyList(), true);
        }
        Search search = new Search(goal, from);
        return search.run(started);
    }

    /** @return the pace the heuristic divides by: measured from the profile once, or yours */
    private Pace pace(PhysicsProfile profile) {
        if (pace == null || paceProfile != profile) {
            Pace measured = Pace.of(profile);
            pace = topSpeed > 0d ? measured.withAcross(topSpeed) : measured;
            paceProfile = profile;
        }
        return pace;
    }

    /** One plan's working state. */
    private final class Search {

        private final Goal goal;
        private final MotionState from;
        private final Vec3 origin;
        /** Where a goal built round a point is, read once: every state also tries heading straight for it. */
        private final Vec3 anchor;
        private final PhysicsProfile profile;
        private final CellCache world;
        private final Pace pace;
        private final DangerField field;
        private final boolean weighDanger;
        private final int range;
        private final int maxStates;
        private final int maxTicks;
        private final double maxDrop;
        private final List<Gait> usable = new ArrayList<>();

        private final PriorityQueue<Node> open = new PriorityQueue<>();
        private final Map<Key, Double> best = new HashMap<>();

        private int expanded;
        private int moves;
        private int simulatedTicks;
        private int duplicates;
        private int outOfRange;
        private int overTime;
        private int refused;
        private int fellTooFar;

        private Search(Goal goal, MotionState from) {
            this.goal = goal;
            this.from = from;
            this.origin = from.getPosition();
            this.anchor = goal.anchor();
            this.profile = simulation.getProfile();
            this.world = new CellCache(simulation.getCollisionSpace());
            this.pace = LocalPlanner.this.pace(profile);
            this.range = Math.max(0, LocalPlanner.this.range.getAsInt());
            this.maxStates = Math.max(0, LocalPlanner.this.maxStates.getAsInt());
            this.maxTicks = Math.max(0, LocalPlanner.this.maxTicks.getAsInt());
            this.maxDrop = LocalPlanner.this.maxDrop.getAsDouble();
            DangerField asked = danger.at(from, maxTicks);
            this.field = asked != null ? asked : DangerField.NONE;
            this.weighDanger = field != DangerField.NONE;
            boolean sprint = canSprint.getAsBoolean();
            for (Gait gait : gaits) {
                if (sprint || !gait.isSprint()) {
                    usable.add(gait);
                }
            }
        }

        private Route run(long started) {
            Node root = new Node(from, null, null, 0, 0, 0d, from.getPosition().getY(), false);
            root.distance = estimate(root);
            root.estimate = root.distance * greed;
            best.put(key(from), 0d);
            open.add(root);
            Node closest = root;
            boolean budgetSpent = false;
            Node reached = null;

            while (!open.isEmpty()) {
                Node node = open.poll();
                Double known = best.get(key(node.state));
                if (known != null && node.cost > known) {
                    continue;
                }
                if (node.reached) {
                    reached = node;
                    break;
                }
                if (expanded >= maxStates) {
                    budgetSpent = true;
                    break;
                }
                expanded++;
                for (int g = 0; g < usable.size(); g++) {
                    Gait gait = usable.get(g);
                    if (!gait.isDirectional()) {
                        closest = offer(node, gait.input(node.yaw()), closest);
                        continue;
                    }
                    for (float yaw : headings) {
                        closest = offer(node, gait.input(yaw), closest);
                    }
                    if (anchor != null) {
                        Vec3 at = node.state.getPosition();
                        double dx = anchor.getX() - at.getX();
                        double dz = anchor.getZ() - at.getZ();
                        if (dx * dx + dz * dz > 1.0E-4d) {
                            closest = offer(node, gait.input((float) Math.toDegrees(Math.atan2(-dx, dz))), closest);
                        }
                    }
                }
            }

            Node end;
            PlanStats.Outcome outcome;
            if (reached != null) {
                end = reached;
                outcome = PlanStats.Outcome.FOUND;
            } else if (closest != root && closest.distance <= root.distance - MIN_PARTIAL_GAIN) {
                end = closest;
                outcome = PlanStats.Outcome.PARTIAL;
            } else {
                end = null;
                outcome = PlanStats.Outcome.NO_ROUTE;
            }
            Route route = end == null ? null : build(end, outcome == PlanStats.Outcome.FOUND);
            lastStats = new PlanStats(outcome, route == null ? 0 : route.getTicks(), expanded, moves,
                    simulatedTicks, duplicates, outOfRange, overTime, refused, fellTooFar, budgetSpent,
                    world.getQueries(), (System.nanoTime() - started) / 1000L);
            return route;
        }

        /**
         * Plays one move out from {@code node} and queues where it ends, unless
         * it was thrown away on the way.
         *
         * @return the closest state to the goal seen so far, which this one may now be
         */
        private Node offer(Node node, MovementInput input, Node closest) {
            moves++;
            MotionState state = node.state;
            double cost = node.cost;
            double peak = node.peak;
            int tick = node.tick;
            boolean reached = false;
            for (int k = 0; k < moveTicks; k++) {
                state = Simulation.step(profile, state, input, world);
                simulatedTicks++;
                tick++;
                if (tick > maxTicks) {
                    overTime++;
                    return closest;
                }
                Vec3 at = state.getPosition();
                if (at.distanceTo(origin) > range) {
                    outOfRange++;
                    return closest;
                }
                if (allowed != null && !allowed.test(state)) {
                    refused++;
                    return closest;
                }
                if (state.isOnGround()) {
                    if (peak - at.getY() > maxDrop) {
                        fellTooFar++;
                        return closest;
                    }
                    peak = at.getY();
                } else {
                    peak = Math.max(peak, at.getY());
                }
                cost += 1d;
                if (weighDanger) {
                    double extra = field.cost(state.hitbox(profile), tick);
                    if (extra > 0d) {
                        cost += extra;
                    }
                }
                if (goal.isMet(at)) {
                    reached = true;
                    break;
                }
            }

            Key key = key(state);
            Double known = best.get(key);
            if (known != null && known <= cost) {
                duplicates++;
                return closest;
            }
            best.put(key, cost);
            Node child = new Node(state, node, input, tick - node.tick, tick, cost, peak, reached);
            child.distance = reached ? 0d : estimate(child);
            child.estimate = child.distance * greed;
            open.add(child);
            return closer(child, closest);
        }

        /** @return the fewest ticks the rules allow from this node to the goal */
        private double estimate(Node node) {
            MotionState state = node.state;
            return pace.ticks(goal.gap(state.getPosition()), state.getVelocity().getY());
        }

        /**
         * @return whichever is the better place to stop short: on the ground over
         *         in the air, then nearer the goal, then sooner
         */
        private Node closer(Node candidate, Node closest) {
            boolean candidateGround = candidate.state.isOnGround();
            boolean closestGround = closest.state.isOnGround();
            if (candidateGround != closestGround) {
                return candidateGround ? candidate : closest;
            }
            if (candidate.distance != closest.distance) {
                return candidate.distance < closest.distance ? candidate : closest;
            }
            return candidate.cost < closest.cost ? candidate : closest;
        }

        /** Replays the chosen moves through the same world, tick by tick, into a route. */
        private Route build(Node end, boolean complete) {
            List<Node> chain = new ArrayList<>();
            for (Node node = end; node.parent != null; node = node.parent) {
                chain.add(node);
            }
            Collections.reverse(chain);
            List<MovementInput> inputs = new ArrayList<>();
            List<MotionState> states = new ArrayList<>();
            MotionState state = from;
            for (Node node : chain) {
                for (int k = 0; k < node.ticks; k++) {
                    state = Simulation.step(profile, state, node.input, world);
                    inputs.add(node.input);
                    states.add(state);
                }
            }
            return Route.precise(goal, from, inputs, states, complete);
        }

        private Key key(MotionState state) {
            Vec3 at = state.getPosition();
            Vec3 v = state.getVelocity();
            return new Key(
                    (int) Math.floor(at.getX() / positionResolution),
                    (int) Math.floor(at.getY() / positionResolution),
                    (int) Math.floor(at.getZ() / positionResolution),
                    (int) Math.floor(Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ()) / velocityResolution),
                    (int) Math.floor(v.getY() / velocityResolution),
                    state.isOnGround());
        }
    }

    /** A state the search reached, and the move that reached it. */
    private static final class Node implements Comparable<Node> {

        private final MotionState state;
        private final Node parent;
        /** The keys held to get here from the parent. */
        private final MovementInput input;
        /** Ticks they were held for. */
        private final int ticks;
        /** Ticks from the start. */
        private final int tick;
        /** Ticks from the start plus danger. */
        private final double cost;
        /** The highest the feet have been since last on the ground, for the drop limit. */
        private final double peak;
        private final boolean reached;
        /** The fewest ticks the rules allow from here to the goal. */
        private double distance;
        /** What the search orders by: {@link #distance} scaled by greed. */
        private double estimate;

        private Node(MotionState state, Node parent, MovementInput input, int ticks, int tick,
                     double cost, double peak, boolean reached) {
            this.state = state;
            this.parent = parent;
            this.input = input;
            this.ticks = ticks;
            this.tick = tick;
            this.cost = cost;
            this.peak = peak;
            this.reached = reached;
        }

        private float yaw() {
            return input == null ? 0f : input.getYaw();
        }

        @Override
        public int compareTo(Node other) {
            int byTotal = Double.compare(cost + estimate, other.cost + other.estimate);
            return byTotal != 0 ? byTotal : Double.compare(estimate, other.estimate);
        }
    }

    /** States closer than the resolution are the same state. */
    private static final class Key {

        private final int x;
        private final int y;
        private final int z;
        /** Horizontal speed, not direction: which way a state is moving matters far less than how fast. */
        private final int speed;
        private final int vy;
        private final boolean ground;

        private Key(int x, int y, int z, int speed, int vy, boolean ground) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.speed = speed;
            this.vy = vy;
            this.ground = ground;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return x == k.x && y == k.y && z == k.z && speed == k.speed && vy == k.vy && ground == k.ground;
        }

        @Override
        public int hashCode() {
            int h = x;
            h = 31 * h + y;
            h = 31 * h + z;
            h = 31 * h + speed;
            h = 31 * h + vy;
            return 31 * h + (ground ? 1 : 0);
        }
    }

    /**
     * Builds a {@link LocalPlanner}.
     *
     * <p>Needs the simulation it plans with. Everything else has a default, and
     * the limits are read live, as each plan begins.
     */
    public static final class Builder {

        private SimulationService simulation;
        private IntSupplier range = () -> DEFAULT_RANGE;
        private IntSupplier maxStates = () -> DEFAULT_MAX_STATES;
        private IntSupplier maxTicks = () -> DEFAULT_MAX_TICKS;
        private int headings = DEFAULT_HEADINGS;
        private int moveTicks = DEFAULT_MOVE_TICKS;
        private Set<Gait> gaits = EnumSet.of(Gait.WALK, Gait.SPRINT, Gait.SPRINT_JUMP);
        private BooleanSupplier canSprint = () -> true;
        private DoubleSupplier maxDrop = () -> Double.POSITIVE_INFINITY;
        private Predicate<MotionState> allowed;
        private Danger danger = Danger.NONE;
        private double positionResolution = DEFAULT_POSITION_RESOLUTION;
        private double velocityResolution = DEFAULT_VELOCITY_RESOLUTION;
        private double greed = DEFAULT_GREED;
        private double topSpeed;

        private Builder() {
        }

        /**
         * The rules and the world: its {@link PhysicsProfile} and
         * {@link dev.px.core.movement.simulation.CollisionSpace}, read as each
         * plan begins. Usually {@code Core.simulation()}.
         */
        public Builder simulation(SimulationService simulation) {
            this.simulation = Validate.notNull(simulation, "simulation");
            return this;
        }

        /** The furthest from its start, in blocks, a plan may go. */
        public Builder range(IntSupplier blocks) {
            this.range = Validate.notNull(blocks, "blocks");
            return this;
        }

        public Builder range(int blocks) {
            Validate.check(blocks > 0, "range must be at least one block, got " + blocks);
            return range(() -> blocks);
        }

        /**
         * How many states one plan may expand: its budget of work. Run out, and
         * the plan returns the route that got closest.
         */
        public Builder maxStates(IntSupplier states) {
            this.maxStates = Validate.notNull(states, "states");
            return this;
        }

        public Builder maxStates(int states) {
            Validate.check(states > 0, "maxStates must be at least 1, got " + states);
            return maxStates(() -> states);
        }

        /** How many ticks a route may take. */
        public Builder maxTicks(IntSupplier ticks) {
            this.maxTicks = Validate.notNull(ticks, "ticks");
            return this;
        }

        public Builder maxTicks(int ticks) {
            Validate.check(ticks > 0, "maxTicks must be at least 1, got " + ticks);
            return maxTicks(() -> ticks);
        }

        /**
         * How many evenly spaced headings each gait is tried at, besides straight
         * at the goal. More finds tighter routes and multiplies the work.
         */
        public Builder headings(int count) {
            Validate.check(count >= 1, "headings must be at least 1, got " + count);
            this.headings = count;
            return this;
        }

        /** How many ticks each move holds its keys. Fewer finds tighter routes and costs more. */
        public Builder moveTicks(int ticks) {
            Validate.check(ticks >= 1, "moveTicks must be at least 1, got " + ticks);
            this.moveTicks = ticks;
            return this;
        }

        /** The gaits tried at every heading. {@link Gait#WAIT} lets a route stop and let danger pass. */
        public Builder gaits(Gait... gaits) {
            Validate.notNull(gaits, "gaits");
            Validate.check(gaits.length > 0, "at least one gait is needed");
            this.gaits = EnumSet.noneOf(Gait.class);
            Collections.addAll(this.gaits, gaits);
            return this;
        }

        /**
         * Whether the player can sprint now, read as each plan begins. When it
         * says no, sprinting gaits are left out. Whether sprinting is allowed &mdash;
         * hunger, an item in use &mdash; is the game's, so this is yours.
         */
        public Builder canSprint(BooleanSupplier canSprint) {
            this.canSprint = Validate.notNull(canSprint, "canSprint");
            return this;
        }

        /**
         * The furthest the player may land below where a fall began, in blocks,
         * read as each plan begins. Unlimited until you set it: how far is safe
         * to fall is a fact about the game.
         */
        public Builder maxDrop(DoubleSupplier blocks) {
            this.maxDrop = Validate.notNull(blocks, "blocks");
            return this;
        }

        public Builder maxDrop(double blocks) {
            Validate.check(blocks >= 0d, "maxDrop must not be negative, got " + blocks);
            return maxDrop(() -> blocks);
        }

        /**
         * Your rule for where the player may be: checked at every simulated tick,
         * and any move that reaches a state it refuses is thrown away. Lava, the
         * void, a protected area &mdash; whatever your world says.
         */
        public Builder allowed(Predicate<MotionState> allowed) {
            this.allowed = Validate.notNull(allowed, "allowed");
            return this;
        }

        /** What makes a route dangerous, weighed against time. {@link Danger#NONE} by default. */
        public Builder danger(Danger danger) {
            this.danger = Validate.notNull(danger, "danger");
            return this;
        }

        /**
         * How close two states are before they count as one: positions in
         * blocks, and speeds &mdash; horizontal speed and vertical velocity &mdash;
         * in blocks a tick. Finer finds tighter routes and costs more.
         */
        public Builder resolution(double position, double velocity) {
            Validate.check(position > 0d, "position resolution must be above zero, got " + position);
            Validate.check(velocity > 0d, "velocity resolution must be above zero, got " + velocity);
            this.positionResolution = position;
            this.velocityResolution = velocity;
            return this;
        }

        /**
         * How much the search leans towards the goal over the quickest route.
         *
         * <p>1 finds the quickest route the moves allow. Above 1 the search trusts
         * its estimate more, expanding far fewer states for a route that may be up
         * to that many times slower. The default, 2, found routes as quick as a
         * stricter search's on open ground, round a wall, over a gap and into a
         * hole, at a small fraction of the work.
         */
        public Builder greed(double weight) {
            Validate.check(weight >= 1d, "greed must be at least 1, got " + weight);
            this.greed = weight;
            return this;
        }

        /**
         * The fastest the player can cover ground, in blocks a tick, for the
         * search's estimate. Measured from the profile on ordinary ground unless
         * you set it; set it for ground faster than that, such as ice, or the
         * estimate can be too high and the route found not the quickest.
         */
        public Builder topSpeed(double blocksPerTick) {
            Validate.check(blocksPerTick > 0d, "top speed must be above zero, got " + blocksPerTick);
            this.topSpeed = blocksPerTick;
            return this;
        }

        public LocalPlanner build() {
            if (simulation == null) {
                throw new IllegalStateException("a LocalPlanner needs: simulation");
            }
            return new LocalPlanner(this);
        }
    }
}
