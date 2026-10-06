package dev.px.core.movement.prediction;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.service.Service;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where somebody else will be: what they are pressing, worked out from how they
 * have moved, run forward through the real movement rules &mdash; and how soon
 * they could be anywhere, however they move.
 *
 * <pre>{@code
 * Core.prediction().setPhysics(myEntityPhysics);                 // their effects and attributes, from your client
 *
 * Prediction next = Core.prediction().predict(target, 6);       // any Tracked entity
 * Vec3 likely = next.positionAt(4);                             // the likeliest
 * int soonest = next.earliestPossible(holeBox);                 // the soonest possible
 *
 * // a future of your own, weighed alongside the rest
 * Prediction holing = Core.prediction().predict(target, 8, Scenario.toward("hole", holeCentre));
 * double odds = holing.chance(state -> inHole(state.getPosition()));
 * }</pre>
 *
 * <h2>How</h2>
 *
 * <ol>
 *   <li><b>History.</b> Each {@link Tracked} keeps where it was over the last few
 *       ticks, and which of those positions were news
 *       ({@link Tracked#isFreshAgo}): servers do not send every entity's position
 *       every tick. Nothing here needs feeding.
 *   <li><b>Rules.</b> Your {@link EntityPhysics} says what it moves by now &mdash;
 *       its effects, attributes, pose &mdash; on top of {@code Core.simulation()}'s
 *       profile, with the hitbox resized to its own.
 *   <li><b>Estimate.</b> Between each two positions that were news, every input it
 *       could have held is run through {@link Simulation} for the ticks between
 *       them, and the one landing closest to where it went is kept: see
 *       {@link MotionEstimate}. Each span starts from the state the last one ended
 *       in, corrected to where the entity really was, so on positions a tick apart
 *       the velocity is exactly what the rules carry.
 *   <li><b>Behaviour.</b> Each new span is also learned from: how fast it moved,
 *       rose and dropped, and how much of it the rules explained. Ground movement
 *       that keeps outrunning the rules scales them up for this entity. See
 *       {@link Behaviour}.
 *   <li><b>Futures.</b> Each {@linkplain #setScenarios scenario} is run forward
 *       from the estimate, with collision, and weighed by how well it would have
 *       predicted the entity's last few spans.
 *   <li><b>Possible.</b> {@link Prediction#earliestPossible} bounds how soon it
 *       could be anywhere, from the fastest its rules or its behaviour allow.
 * </ol>
 *
 * <h2>What it cannot know</h2>
 *
 * <p>A player can change their mind on any tick, and nothing in their past says
 * when they will. The futures are what the evidence supports, not what will
 * happen. Knockback, water, ladders, elytra and anything else {@link Simulation}
 * does not model show up as error and lower the weight of every future; tell it
 * through {@link EntityPhysics} when you know.
 *
 * <p><b>Feed it the server's positions.</b> The position the game draws for
 * another player is eased toward the one the server sent over a few ticks, which
 * smooths and delays every move. Report the position the server last sent, and a
 * {@linkplain dev.px.core.entity.EntitySource#positionStamp stamp} for when it did.
 *
 * <h2>Cost</h2>
 *
 * <p>With H the history kept, S the scenarios, B the backtest starts and T the
 * ticks predicted: about 15·H simulated steps to estimate, plus S·(B + 1)·T to
 * predict; twice the estimate when the entity's learned speed differs from its
 * rules. A step sweeps a hitbox against the boxes near it, fetched from your
 * {@link CollisionSpace} once per prediction and reused. Game thread only.
 */
public final class PredictionService implements Service {

    /** Backtest starts unless {@link #setBacktest} says otherwise. */
    public static final int DEFAULT_BACKTEST = 4;

    /** Blocks of error per e-fold of weight unless {@link #setTemperature} says otherwise. A tuning knob. */
    public static final double DEFAULT_TEMPERATURE = 0.15d;

    /** Blocks of error a reliable prediction stays within unless {@link #setReliableError} says otherwise. */
    public static final double DEFAULT_RELIABLE_ERROR = 0.5d;

    /** How close, in blocks, feet must be to a surface to stand on it unless {@link #setGroundTolerance} says otherwise. */
    public static final double DEFAULT_GROUND_TOLERANCE = 0.01d;

    /** Blocks a move may miss by and still count as explained by the rules unless {@link #setExplainedError} says otherwise. */
    public static final double DEFAULT_EXPLAINED_ERROR = 0.05d;

    /** Spans each entity's behaviour is learned from unless {@link #setLearningWindow} says otherwise: about ten seconds. */
    public static final int DEFAULT_LEARNING_WINDOW = 200;

    /** Blocks per tick past which a move is a teleport, not movement, unless {@link #setTeleportSpeed} says otherwise. */
    public static final double DEFAULT_TELEPORT_SPEED = 8d;

    /** Ground moves needed before a learned speed factor is believed. */
    private static final int SPEED_SAMPLES = 10;

    /** How far from 1 a learned speed factor must be to change the rules: under it, the rules stand. */
    private static final double SPEED_SLACK = 0.05d;

    /** How far past the furthest it could reach the world is fetched, in blocks, so nearly every step finds it cached. */
    private static final double FETCH_MARGIN = 2d;

    private final SimulationService simulation;
    private EntityPhysics physics = EntityPhysics.RULES;
    private List<Scenario> scenarios = Collections.unmodifiableList(
            Arrays.asList(Scenario.HOLDS, Scenario.STOPS, Scenario.CARRIES));
    private int backtest = DEFAULT_BACKTEST;
    private double temperature = DEFAULT_TEMPERATURE;
    private double reliableError = DEFAULT_RELIABLE_ERROR;
    private double groundTolerance = DEFAULT_GROUND_TOLERANCE;
    private double explainedError = DEFAULT_EXPLAINED_ERROR;
    private int learningWindow = DEFAULT_LEARNING_WINDOW;
    private double teleportSpeed = DEFAULT_TELEPORT_SPEED;

    private final Map<Tracked<?>, Learner> learners = new IdentityHashMap<>();
    private final Map<PhysicsProfile, Double> fastest = new WeakHashMap<>();
    private int sincePurge;

    public PredictionService(SimulationService simulation) {
        this.simulation = Validate.notNull(simulation, "simulation");
    }

    @Override
    public String getName() {
        return "Prediction";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        learners.clear();
    }

    // ------------------------------------------------------------ settings

    /** What each entity moves by: its effects, attributes and pose. {@link EntityPhysics#RULES} unless set. */
    public void setPhysics(EntityPhysics physics) {
        this.physics = Validate.notNull(physics, "physics");
    }

    /**
     * The futures every prediction weighs: {@link Scenario#HOLDS},
     * {@link Scenario#STOPS} and {@link Scenario#CARRIES} unless set.
     */
    public void setScenarios(Scenario... scenarios) {
        Validate.check(scenarios.length > 0, "at least one scenario is needed");
        for (Scenario scenario : scenarios) {
            Validate.notNull(scenario, "scenario");
        }
        this.scenarios = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(scenarios)));
    }

    public List<Scenario> getScenarios() {
        return scenarios;
    }

    /** @param starts how many past spans each scenario is tested from to weigh it; more is steadier and costs more */
    public void setBacktest(int starts) {
        Validate.check(starts >= 1, "backtest needs at least one start");
        this.backtest = starts;
    }

    /**
     * @param blocks how sharply error turns into weight: a scenario this many
     *        blocks worse than another gets about a third of its weight
     */
    public void setTemperature(double blocks) {
        Validate.check(blocks > 0d, "temperature must be positive");
        this.temperature = blocks;
    }

    /** @param blocks the backtest error a prediction's likeliest future must stay within to be reliable */
    public void setReliableError(double blocks) {
        Validate.check(blocks > 0d, "reliable error must be positive");
        this.reliableError = blocks;
    }

    /** @param blocks how close feet must be to the top of a box to stand on it: enough to cover how coarsely positions arrive */
    public void setGroundTolerance(double blocks) {
        Validate.check(blocks >= 0d, "ground tolerance must not be negative");
        this.groundTolerance = blocks;
    }

    /**
     * @param blocks how far the best-fitting keys may miss a move for the rules to
     *        count as explaining it: enough to cover how coarsely positions arrive
     */
    public void setExplainedError(double blocks) {
        Validate.check(blocks > 0d, "explained error must be positive");
        this.explainedError = blocks;
    }

    /** @param spans how many of an entity's latest spans its behaviour is learned from */
    public void setLearningWindow(int spans) {
        Validate.check(spans >= 1, "learning window must hold at least one span");
        this.learningWindow = spans;
    }

    /** @param blocksPerTick a move faster than this is a teleport, a pearl or a setback, and is not learned from */
    public void setTeleportSpeed(double blocksPerTick) {
        Validate.check(blocksPerTick > 0d, "teleport speed must be positive");
        this.teleportSpeed = blocksPerTick;
    }

    // ------------------------------------------------------------- queries

    /**
     * @return what {@code entity} is doing now, from its history; with fewer than
     *         three positions that were news there is nothing to fit, and the
     *         estimate holds no keys and an error of NaN
     */
    public MotionEstimate estimate(Tracked<?> entity) {
        Validate.notNull(entity, "entity");
        Rules rules = rules(entity);
        Behaviour behaviour = learn(entity, rules);
        return new Reader(entity, rules.effective(behaviour), 0).estimate(0);
    }

    /**
     * @return the rules {@code entity} is predicted by now: the simulation's
     *         profile, resized to its hitbox, as your {@link EntityPhysics} adjusts
     *         it; null when it says the entity is not moving by the rules
     */
    public PhysicsProfile rulesOf(Tracked<?> entity) {
        Validate.notNull(entity, "entity");
        return rules(entity).profile;
    }

    /** @return the simulation whose profile and world predictions use */
    public SimulationService getSimulation() {
        return simulation;
    }

    /** @return how {@code entity} has been seen to move, brought up to date */
    public Behaviour behaviour(Tracked<?> entity) {
        Validate.notNull(entity, "entity");
        return learn(entity, rules(entity));
    }

    /**
     * @return where {@code entity} may be over the next {@code ticks} ticks: the
     *         service's scenarios and {@code extra}, each played out and weighed,
     *         and how soon it could be anywhere
     */
    public Prediction predict(Tracked<?> entity, int ticks, Scenario... extra) {
        Validate.notNull(entity, "entity");
        Validate.check(ticks >= 1, "ticks must be at least 1");
        List<Scenario> all = new ArrayList<>(scenarios);
        for (Scenario scenario : extra) {
            all.add(Validate.notNull(scenario, "scenario"));
        }
        Rules rules = rules(entity);
        Behaviour behaviour = learn(entity, rules);
        PhysicsProfile effective = rules.effective(behaviour);
        Reader reader = new Reader(entity, effective, ticks);
        MotionEstimate now = reader.estimate(0);
        int age = now.getTicksAgo();

        List<Future> futures = new ArrayList<>(all.size());
        boolean known = true;
        if (effective == null) {
            // Not moving by the rules: all there is to go on is how it has been moving.
            futures.add(new Future(Scenario.CARRIES, reader.drift(now, age, 0).get(0),
                    reader.drift(now, age, ticks).subList(1, ticks + 1), Double.NaN, 1d));
            known = false;
        } else {
            for (Scenario scenario : all) {
                double error = reader.backtest(scenario, ticks);
                known &= !Double.isNaN(error);
                List<MotionState> trace = reader.play(scenario, now, age + ticks);
                MotionState start = age == 0 ? now.getState() : trace.get(age - 1);
                futures.add(new Future(scenario, start, trace.subList(age, trace.size()), error, 0d));
            }
            weigh(futures, known);
            futures.sort((a, b) -> Double.compare(b.getWeight(), a.getWeight()));
        }
        Future best = futures.get(0);
        boolean reliable = known && best.getError() <= reliableError;
        return new Prediction(entity, now, futures, ticks, reliable, behaviour,
                envelope(now, rules, behaviour));
    }

    /**
     * Weight by error and prior: prior times e^(-error / temperature), shared out to
     * sum to 1; by prior alone while errors are unknown.
     */
    private void weigh(List<Future> futures, boolean known) {
        double least = Double.POSITIVE_INFINITY;
        for (Future future : futures) {
            least = Math.min(least, future.getError());
        }
        double[] raw = new double[futures.size()];
        double total = 0d;
        for (int i = 0; i < raw.length; i++) {
            // Relative to the best, so large errors all round do not underflow to nothing.
            Future future = futures.get(i);
            raw[i] = future.getScenario().getPrior()
                    * (known ? Math.exp(-(future.getError() - least) / temperature) : 1d);
            total += raw[i];
        }
        for (int i = 0; i < raw.length; i++) {
            futures.set(i, futures.get(i).withWeight(raw[i] / total));
        }
    }

    // ----------------------------------------------------------- the rules

    /** What one entity moves by: the profile your physics gave, or null when it is not moving by the rules. */
    private Rules rules(Tracked<?> entity) {
        PhysicsProfile base = simulation.getProfile();
        if (entity.getWidth() > 0d && entity.getHeight() > 0d) {
            base = base.withHitbox(entity.getWidth(), entity.getHeight());
        }
        return new Rules(physics.profile(entity, base), base);
    }

    private static final class Rules {
        /** Null when it is not moving by the rules. */
        final PhysicsProfile profile;
        /** Its hitbox and how things fall, for the possible bound when it is not moving by the rules. */
        final PhysicsProfile base;

        Rules(PhysicsProfile profile, PhysicsProfile base) {
            this.profile = profile;
            this.base = base;
        }

        /** @return the rules with its learned speed applied; null when it is not moving by them */
        PhysicsProfile effective(Behaviour behaviour) {
            if (profile == null) {
                return null;
            }
            double factor = behaviour.getSpeedFactor();
            if (behaviour.getSpeedSamples() < SPEED_SAMPLES || Math.abs(factor - 1d) <= SPEED_SLACK) {
                return profile;
            }
            return profile.withMoveSpeedAttribute(profile.getMoveSpeedAttribute() * factor);
        }
    }

    /** The possible bound: the fastest of its rules and what it has been seen to do. */
    private Envelope envelope(MotionEstimate now, Rules rules, Behaviour behaviour) {
        PhysicsProfile known = rules.profile != null ? rules.effective(behaviour) : rules.base;
        double speed = Math.max(fastestByRules(known), behaviour.getTopSpeed());
        double rise = Math.max(known.getJumpVelocity(), behaviour.getTopRise());
        Box box = now.getState().hitbox(known);
        return new Envelope(box, speed, rise, behaviour.getTopDrop(), known.getGravity(), known.getDrag(),
                now.getState().getVelocity().getY());
    }

    /** @return the most ground the rules let it cover in a tick: sprint-jumping, worked out once per profile */
    private double fastestByRules(PhysicsProfile profile) {
        Double known = fastest.get(profile);
        if (known != null) {
            return known;
        }
        CollisionSpace floor = region -> Collections.singletonList(Box.of(
                region.getMinX() - 1d, -1d, region.getMinZ() - 1d, region.getMaxX() + 1d, 0d, region.getMaxZ() + 1d));
        MovementInput sprintJump = MovementInput.forward(0f).withSprint(true).withJump(true);
        MotionState state = MotionState.at(Vec3.ZERO);
        double most = 0d;
        for (int tick = 0; tick < 80; tick++) {
            MotionState next = Simulation.step(profile, state, sprintJump, floor);
            double dx = next.getPosition().getX() - state.getPosition().getX();
            double dz = next.getPosition().getZ() - state.getPosition().getZ();
            most = Math.max(most, Math.sqrt(dx * dx + dz * dz));
            state = next;
        }
        fastest.put(profile, most);
        return most;
    }

    // ------------------------------------------------------------ learning

    /** Brings {@code entity}'s behaviour up to date with the spans it has not been learned from yet. */
    private Behaviour learn(Tracked<?> entity, Rules rules) {
        if (++sincePurge >= 64) {
            sincePurge = 0;
            Iterator<Tracked<?>> held = learners.keySet().iterator();
            while (held.hasNext()) {
                if (!held.next().isTracked()) {
                    held.remove();
                }
            }
        }
        Learner learner = learners.get(entity);
        if (learner == null) {
            learner = new Learner(learningWindow);
            learners.put(entity, learner);
        }
        Reader reader = new Reader(entity, rules.profile, 0);
        long now = entity.getTick();
        List<Integer> fresh = reader.fresh;
        // Oldest first, so the window fills in order.
        for (int k = fresh.size() - 2; k >= 0; k--) {
            long end = now - fresh.get(k);
            if (end <= learner.learnedTo) {
                continue;
            }
            learner.learnedTo = end;
            int gap = fresh.get(k + 1) - fresh.get(k);
            Vec3 move = entity.positionAgo(fresh.get(k)).subtract(entity.positionAgo(fresh.get(k + 1)));
            double across = Math.sqrt(move.getX() * move.getX() + move.getZ() * move.getZ()) / gap;
            if (across > teleportSpeed || Math.abs(move.getY()) / gap > teleportSpeed) {
                continue;
            }
            Fit fit = reader.fit(k);
            learner.add(gap, across, Math.max(0d, move.getY()) / gap, Math.max(0d, -move.getY()) / gap,
                    fit == null ? Double.NaN : fit.error, fit == null ? Double.NaN : fit.speedRatio());
        }
        return learner.snapshot(explainedError);
    }

    /** One entity's behaviour, over a window of its latest spans. */
    private static final class Learner {
        long learnedTo = Long.MIN_VALUE;
        final int[] gaps;
        final double[] speeds;
        final double[] rises;
        final double[] drops;
        final double[] errors;
        final double[] ratios;
        int head;
        int size;

        Learner(int window) {
            gaps = new int[window];
            speeds = new double[window];
            rises = new double[window];
            drops = new double[window];
            errors = new double[window];
            ratios = new double[window];
        }

        void add(int gap, double speed, double rise, double drop, double error, double ratio) {
            gaps[head] = gap;
            speeds[head] = speed;
            rises[head] = rise;
            drops[head] = drop;
            errors[head] = error;
            ratios[head] = ratio;
            head = (head + 1) % gaps.length;
            if (size < gaps.length) {
                size++;
            }
        }

        Behaviour snapshot(double explainedError) {
            if (size == 0) {
                return Behaviour.NONE;
            }
            double topSpeed = 0d;
            double topRise = 0d;
            double topDrop = 0d;
            int fitted = 0;
            int explained = 0;
            double[] measured = new double[size];
            int ratioCount = 0;
            int[] gapCounts = new int[9];
            for (int i = 0; i < size; i++) {
                topSpeed = Math.max(topSpeed, speeds[i]);
                topRise = Math.max(topRise, rises[i]);
                topDrop = Math.max(topDrop, drops[i]);
                if (!Double.isNaN(errors[i])) {
                    fitted++;
                    if (errors[i] <= explainedError) {
                        explained++;
                    }
                }
                if (!Double.isNaN(ratios[i])) {
                    measured[ratioCount++] = ratios[i];
                }
                gapCounts[Math.min(gaps[i], gapCounts.length - 1)]++;
            }
            int typical = 0;
            for (int gap = 1; gap < gapCounts.length; gap++) {
                if (gapCounts[gap] > gapCounts[typical]) {
                    typical = gap;
                }
            }
            double factor = 1d;
            if (ratioCount > 0) {
                Arrays.sort(measured, 0, ratioCount);
                factor = ratioCount % 2 == 1 ? measured[ratioCount / 2]
                        : (measured[ratioCount / 2 - 1] + measured[ratioCount / 2]) / 2d;
            }
            return new Behaviour(size, topSpeed, topRise, topDrop, factor, ratioCount,
                    fitted == 0 ? Double.NaN : explained / (double) fitted, typical);
        }
    }

    // ----------------------------------------------------------- internals

    /**
     * One entity's history, read for one query under one profile: the world near
     * it fetched once, and each span's fit worked out at most once.
     */
    private final class Reader {
        private final Tracked<?> entity;
        /** Null when it is not moving by the rules: only its positions are read. */
        private final PhysicsProfile profile;
        private final CollisionSpace space;
        /** Ticks ago of each position that was news, newest first. */
        final List<Integer> fresh = new ArrayList<>();
        private final Fit[] fits;
        private final MotionState[] states;

        Reader(Tracked<?> entity, PhysicsProfile profile, int ticksAhead) {
            this.entity = entity;
            this.profile = profile;
            int size = entity.getHistorySize();
            for (int i = 0; i < size; i++) {
                if (entity.isFreshAgo(i)) {
                    fresh.add(i);
                }
            }
            if (fresh.isEmpty() && size > 0) {
                fresh.add(0);
            }
            this.fits = new Fit[fresh.size()];
            this.states = new MotionState[fresh.size()];
            this.space = profile == null ? CollisionSpace.empty()
                    : new NearbySpace(simulation.getCollisionSpace(), reach(ticksAhead));
        }

        /** Everywhere the history went, grown by how far a future could get: what to fetch once. */
        private Box reach(int ticksAhead) {
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            double fastestMove = 0d;
            Vec3 previous = null;
            for (int i = entity.getHistorySize() - 1; i >= 0; i--) {
                Vec3 p = entity.positionAgo(i);
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
                if (previous != null) {
                    fastestMove = Math.max(fastestMove, p.distanceTo(previous));
                }
                previous = p;
            }
            double grow = FETCH_MARGIN + fastestMove * (ticksAhead + entity.getTicksSinceFresh() + 1);
            return Box.of(minX - grow, minY - grow, minZ - grow,
                    maxX + grow, maxY + profile.getHitboxHeight() + grow, maxZ + grow);
        }

        /** What it was doing as of fresh position {@code k} (0 the newest): the fit there, with a jump's keys carried. */
        MotionEstimate estimate(int k) {
            int ticksAgo = fresh.isEmpty() ? 0 : fresh.get(k);
            Fit latest = fit(k);
            if (latest == null) {
                return unfitted(k, ticksAgo);
            }
            MovementInput keys = latest.input;
            boolean known = latest.from.isOnGround();
            if (!known) {
                // In the air the keys barely move it: take them from the span it left the ground.
                for (int i = k + 1; i < fresh.size(); i++) {
                    Fit earlier = fit(i);
                    if (earlier == null) {
                        break;
                    }
                    if (earlier.from.isOnGround()) {
                        MovementInput off = earlier.input;
                        MovementInput steer = latest.input.isMoving() || !off.isMoving() ? latest.input : off;
                        keys = MovementInput.of(steer.getYaw(), steer.getForward(), steer.getStrafe())
                                .withSprint(off.isSprint()).withSneak(off.isSneak()).withJump(off.isJump());
                        known = true;
                        break;
                    }
                }
            }
            return new MotionEstimate(ticksAgo, state(k), keys, latest.error, known, latest.move());
        }

        private MotionEstimate unfitted(int k, int ticksAgo) {
            Vec3 at = entity.positionAgo(ticksAgo);
            MotionState now;
            Vec3 move = Vec3.ZERO;
            if (k + 1 < fresh.size()) {
                now = profile == null ? MotionState.of(at, Vec3.ZERO, false) : state(k);
                int gap = fresh.get(k + 1) - ticksAgo;
                move = at.subtract(entity.positionAgo(fresh.get(k + 1))).scale(1d / gap);
            } else {
                now = MotionState.of(at, Vec3.ZERO, profile != null && standsOn(at));
            }
            return new MotionEstimate(ticksAgo, now, MovementInput.none(entity.yawAgo(ticksAgo)), Double.NaN,
                    false, move);
        }

        /** @return the future {@code scenario} plays out from {@code start}, {@code ticks} long */
        List<MotionState> play(Scenario scenario, MotionEstimate start, int ticks) {
            List<MotionState> trace = new ArrayList<>(ticks);
            MotionState state = start.getState();
            for (int tick = 0; tick < ticks; tick++) {
                state = scenario.adjust(start, state, tick);
                state = Simulation.step(profile, state, scenario.input(start, state, tick), space);
                trace.add(state);
            }
            return trace;
        }

        /**
         * @return a straight line along its last move, for something not moving by
         *         the rules: the first element is now, then one a tick
         */
        List<MotionState> drift(MotionEstimate start, int age, int ticks) {
            List<MotionState> trace = new ArrayList<>(ticks + 1);
            Vec3 move = start.getMove();
            Vec3 at = start.getState().getPosition().add(move.scale(age));
            for (int tick = 0; tick <= ticks; tick++) {
                trace.add(MotionState.of(at.add(move.scale(tick)), move, false));
            }
            return trace;
        }

        /**
         * @return the mean distance between where {@code scenario}, started from a
         *         few positions back, put the entity {@code ticks} or so later and
         *         where it really was; NaN with too little history to start from
         *         anywhere. A start in mid-air whose take-off is older than the
         *         history is skipped: its keys are unknown, not wrong
         */
        double backtest(Scenario scenario, int ticks) {
            double total = 0d;
            int counted = 0;
            for (int k = 0; k < fresh.size() && counted < backtest; k++) {
                int from = fresh.get(k);
                if (from < ticks) {
                    continue;
                }
                if (fit(k) == null) {
                    break;
                }
                // The latest position that was news at least `ticks` after the start.
                int to = -1;
                for (int e = k - 1; e >= 0; e--) {
                    if (fresh.get(e) <= from - ticks) {
                        to = e;
                        break;
                    }
                }
                if (to < 0) {
                    continue;
                }
                MotionEstimate then = estimate(k);
                if (!then.isKnown()) {
                    continue;
                }
                List<MotionState> trace = play(scenario, then, from - fresh.get(to));
                total += trace.get(trace.size() - 1).getPosition().distanceTo(entity.positionAgo(fresh.get(to)));
                counted++;
            }
            return counted == 0 ? Double.NaN : total / counted;
        }

        /**
         * @return the state at fresh position {@code k}: the end of the span into
         *         it, corrected to where it really was
         */
        MotionState state(int k) {
            if (states[k] != null) {
                return states[k];
            }
            MotionState state;
            Fit fit = fit(k);
            if (fit == null) {
                state = first(k);
            } else {
                Vec3 at = entity.positionAgo(fresh.get(k));
                MotionState end = fit.end;
                // The rules carried it to `end`; it was really at `at`. On a span of one tick, adding back what
                // that difference does to the velocity makes it exactly the velocity the rules carry from `at`.
                Vec3 missed = at.subtract(end.getPosition()).scale(1d / fit.gap);
                boolean grounded = fit.error <= explainedError ? end.isOnGround() : grounded(k);
                double friction = fit.lastStartGrounded
                        ? space.slipperinessAt(fit.lastStart.add(0d, -1d, 0d)) * profile.getGroundFriction()
                        : profile.getGroundFriction();
                double vertical = grounded
                        ? -profile.getGravity() * profile.getDrag()
                        : end.getVelocity().getY() + missed.getY() * profile.getDrag();
                state = MotionState.of(at, Vec3.of(end.getVelocity().getX() + missed.getX() * friction, vertical,
                        end.getVelocity().getZ() + missed.getZ() * friction), grounded);
            }
            states[k] = state;
            return state;
        }

        /**
         * @return the state at a position no span leads into &mdash; the oldest
         *         fitted from, or the first after a teleport: its velocity from the
         *         move into it, as if a tick, or still when that move was a teleport
         */
        private MotionState first(int k) {
            Vec3 at = entity.positionAgo(fresh.get(k));
            if (k + 1 >= fresh.size()) {
                return MotionState.of(at, Vec3.ZERO, standsOn(at));
            }
            Vec3 before = entity.positionAgo(fresh.get(k + 1));
            int gap = fresh.get(k + 1) - fresh.get(k);
            Vec3 move = at.subtract(before).scale(1d / gap);
            if (move.length() > teleportSpeed) {
                return MotionState.of(at, Vec3.ZERO, standsOn(at));
            }
            boolean grounded = standsOn(at);
            double friction = grounded
                    ? space.slipperinessAt(at.add(0d, -1d, 0d)) * profile.getGroundFriction()
                    : profile.getGroundFriction();
            double fall = (grounded ? 0d : move.getY()) - profile.getGravity();
            return MotionState.of(at, Vec3.of(move.getX() * friction, fall * profile.getDrag(),
                    move.getZ() * friction), grounded);
        }

        /**
         * @return the keys that best explain the span into fresh position {@code k};
         *         null without two positions before it &mdash; one to start from, and
         *         one to say how fast it was going &mdash; across a teleport, or when it
         *         is not moving by the rules
         */
        Fit fit(int k) {
            if (profile == null || k + 2 >= fresh.size()) {
                return null;
            }
            if (fits[k] != null) {
                return fits[k] == Fit.NONE ? null : fits[k];
            }
            int gap = fresh.get(k + 1) - fresh.get(k);
            Vec3 target = entity.positionAgo(fresh.get(k));
            Vec3 start = entity.positionAgo(fresh.get(k + 1));
            if (target.distanceTo(start) / gap > teleportSpeed) {
                fits[k] = Fit.NONE;
                return null;
            }
            MotionState from = state(k + 1);
            MovementInput none = MovementInput.none(entity.yawAgo(fresh.get(k)));
            Fit coasted = run(from, none, gap, start, target, null);
            Fit best = coasted;
            double dx = target.getX() - coasted.end.getPosition().getX();
            double dz = target.getZ() - coasted.end.getPosition().getZ();
            boolean pushed = dx * dx + dz * dz > 1e-12;
            float heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
            for (int jump = 0; jump < (from.isOnGround() ? 2 : 1); jump++) {
                boolean jumping = jump == 1;
                if (jumping) {
                    best = better(best, run(from, MovementInput.none(heading).withJump(true), gap, start, target,
                            coasted.end));
                }
                if (!pushed) {
                    continue;
                }
                for (int gait = 0; gait < 3; gait++) {
                    for (int diagonal = 0; diagonal < 2; diagonal++) {
                        // Two keys move 45 degrees off the way it faces: face 45 the other way.
                        MovementInput keys = diagonal == 0
                                ? MovementInput.of(heading, 1d, 0d) : MovementInput.of(heading + 45f, 1d, 1d);
                        keys = keys.withSprint(gait == 1).withSneak(gait == 2).withJump(jumping);
                        best = better(best, run(from, keys, gap, start, target, coasted.end));
                    }
                }
            }
            fits[k] = best;
            return best;
        }

        private Fit better(Fit best, Fit candidate) {
            return candidate.error < best.error - 1e-9 ? candidate : best;
        }

        /** @return {@code keys} held from {@code from} for {@code ticks}, judged against {@code target} */
        private Fit run(MotionState from, MovementInput keys, int ticks, Vec3 start, Vec3 target, MotionState coasted) {
            MotionState state = from;
            MotionState lastStart = from;
            for (int tick = 0; tick < ticks; tick++) {
                lastStart = state;
                state = Simulation.step(profile, state, keys, space);
            }
            return new Fit(from, keys, state, coasted != null ? coasted : state, ticks, start, target, lastStart);
        }

        /**
         * @return whether it ended the span into fresh position {@code k} on the
         *         ground, as the rules mean it: its fall was stopped. The rules move
         *         it up or down before sideways, so that is asked where it was as
         *         well as where it is: on the tick it walks off an edge it is still
         *         standing, the edge having stopped its fall before it moved past.
         */
        boolean grounded(int k) {
            Vec3 now = entity.positionAgo(fresh.get(k));
            if (standsOn(now)) {
                return true;
            }
            if (k + 1 >= fresh.size()) {
                return false;
            }
            Vec3 before = entity.positionAgo(fresh.get(k + 1));
            return standsOn(Vec3.of(before.getX(), now.getY(), before.getZ()));
        }

        /** @return whether feet at {@code at} stand on the top of a box */
        boolean standsOn(Vec3 at) {
            double half = profile.getHitboxWidth() / 2d;
            Box under = Box.of(at.getX() - half, at.getY() - groundTolerance - 1e-7, at.getZ() - half,
                    at.getX() + half, at.getY() + groundTolerance, at.getZ() + half);
            for (Box box : space.boxesIn(under)) {
                if (Math.abs(box.getMaxY() - at.getY()) <= groundTolerance
                        && box.getMaxX() > under.getMinX() && box.getMinX() < under.getMaxX()
                        && box.getMaxZ() > under.getMinZ() && box.getMinZ() < under.getMaxZ()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The keys that best explained one span, where they put it, and by how much they missed. */
    private static final class Fit {
        static final Fit NONE = new Fit(null, null, null, null, 1, null, null, null);

        final MotionState from;
        final MovementInput input;
        final MotionState end;
        /** Where no keys would have left it. */
        final MotionState coasted;
        final int gap;
        final Vec3 start;
        final Vec3 target;
        final double error;
        /** Where the span's last tick started, and whether on the ground: the friction that tick applied. */
        final Vec3 lastStart;
        final boolean lastStartGrounded;

        Fit(MotionState from, MovementInput input, MotionState end, MotionState coasted, int gap, Vec3 start,
            Vec3 target, MotionState lastStart) {
            this.from = from;
            this.input = input;
            this.end = end;
            this.coasted = coasted;
            this.gap = gap;
            this.start = start;
            this.target = target;
            this.error = end == null ? Double.NaN : end.getPosition().distanceTo(target);
            this.lastStart = lastStart == null ? null : lastStart.getPosition();
            this.lastStartGrounded = lastStart != null && lastStart.isOnGround();
        }

        /** @return how far it moved per tick over the span */
        Vec3 move() {
            return target.subtract(start).scale(1d / gap);
        }

        /**
         * @return how much further it went sideways, beyond where no keys would
         *         have left it, than its best-fitting keys would have taken it: NaN
         *         unless it was walking on the ground
         */
        double speedRatio() {
            if (!from.isOnGround() || !input.isMoving()) {
                return Double.NaN;
            }
            double fx = end.getPosition().getX() - coasted.getPosition().getX();
            double fz = end.getPosition().getZ() - coasted.getPosition().getZ();
            double keyed = Math.sqrt(fx * fx + fz * fz);
            if (keyed < 1e-6) {
                return Double.NaN;
            }
            double ox = target.getX() - coasted.getPosition().getX();
            double oz = target.getZ() - coasted.getPosition().getZ();
            return Math.sqrt(ox * ox + oz * oz) / keyed;
        }
    }

    /**
     * The world near one entity, fetched once: a prediction steps hundreds of
     * times through the same few blocks, and asking the game each time would be
     * most of its cost. A step outside what was fetched asks the world directly.
     */
    private static final class NearbySpace implements CollisionSpace {
        private final CollisionSpace world;
        private final Box fetched;
        private final List<Box> boxes;

        NearbySpace(CollisionSpace world, Box region) {
            this.world = world;
            this.fetched = region;
            List<Box> found = world.boxesIn(region);
            this.boxes = found == null ? Collections.<Box>emptyList() : found;
        }

        @Override
        public List<Box> boxesIn(Box region) {
            if (region.getMinX() < fetched.getMinX() || region.getMinY() < fetched.getMinY()
                    || region.getMinZ() < fetched.getMinZ() || region.getMaxX() > fetched.getMaxX()
                    || region.getMaxY() > fetched.getMaxY() || region.getMaxZ() > fetched.getMaxZ()) {
                return world.boxesIn(region);
            }
            List<Box> inside = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                Box box = boxes.get(i);
                if (box.getMaxX() >= region.getMinX() && box.getMinX() <= region.getMaxX()
                        && box.getMaxY() >= region.getMinY() && box.getMinY() <= region.getMaxY()
                        && box.getMaxZ() >= region.getMinZ() && box.getMinZ() <= region.getMaxZ()) {
                    inside.add(box);
                }
            }
            return inside;
        }

        @Override
        public double slipperinessAt(Vec3 position) {
            return world.slipperinessAt(position);
        }
    }
}
