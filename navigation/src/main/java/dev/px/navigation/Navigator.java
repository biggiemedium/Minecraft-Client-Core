package dev.px.navigation;

import dev.px.core.control.ControlService;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.rotation.RotationPriority;
import dev.px.core.movement.rotation.RotationRequest;
import dev.px.core.movement.rotation.RotationService;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.PathProvider;
import dev.px.core.navigation.Progress;
import dev.px.core.navigation.Route;
import dev.px.core.util.Validate;
import dev.px.navigation.plan.LocalPlanner;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * Gets the player to a {@link Goal}, through any {@link PathProvider}, by
 * claiming Core's controls.
 *
 * <pre>{@code
 * Navigator navigator = Navigator.builder()
 *         .provider(planner)                         // the LocalPlanner, any planner, or one that drives
 *         .controls(Core.controls())
 *         .rotations(Core.rotations())
 *         .simulation(Core.simulation())
 *         .priority(RotationPriority.NORMAL)
 *         .build();
 *
 * navigator.travel(Goal.block(x, y, z));
 *
 * // every tick, with the player's state before it moves
 * Progress progress = navigator.tick(player);
 * if (progress.isDone()) { ... }
 * }</pre>
 *
 * <h2>Following a route</h2>
 *
 * <p>A <b>precise</b> route &mdash; every tick's keys and the state expected
 * after it, as the {@link LocalPlanner} plans &mdash; is followed key for key: the
 * movement keys and the yaw they are meant for are claimed each tick, at the
 * navigator's priority. Before each tick the navigator checks the player is where
 * the route expected, as Core's {@code DriftMonitor} checks the simulation, and
 * plans again the moment they part. Every few ticks it also plays the rest of
 * the route through the world as it is now, and plans again if it no longer ends
 * where it did: a block placed in the way is noticed before the player walks into
 * it.
 *
 * <p>A <b>coarse</b> route &mdash; positions only, from a long-range pathfinder
 * &mdash; is followed a stretch at a time: the next few waypoints within
 * {@linkplain Builder#sectionReach reach} become a goal for the
 * {@linkplain Builder#local local planner}, whose precise route is followed as
 * above. Without a local planner, the navigator steers: it faces the next
 * waypoint, walks, and jumps when the next one is higher than a step.
 *
 * <p>A provider that <b>drives</b> &mdash; Baritone, through your adapter &mdash;
 * is asked to follow the goal every tick, and its {@link Progress} is passed on.
 *
 * <p>A goal that follows something is planned for again once it has moved
 * further than {@linkplain Builder#goalMoved a set distance}.
 *
 * <h2>Arriving, failing, being stuck</h2>
 *
 * <p>Once the goal is met the navigator lets go of the controls and reports
 * {@link Progress.State#ARRIVED}; it keeps the goal, so a goal that moves away is
 * followed again. When no route can be found it reports
 * {@link Progress.State#FAILED} with the planner's reason, lets go, and stays
 * failed until the next {@link #travel}. When the goal has come no closer for
 * {@linkplain Builder#stuckTicks a while}, or the route keeps parting from the
 * player, it reports {@link Progress.State#STUCK} and keeps trying: what to do
 * about it is yours.
 *
 * <p>Claims expire like every claim in Core, so a navigator that is no longer
 * ticked lets go of the controls on its own.
 *
 * <p>Game thread only.
 */
public final class Navigator {

    /** Blocks between where a route expected the player and where they are before it is planned again, by default. */
    public static final double DEFAULT_DRIFT = 0.3d;

    /** Blocks a followed goal may move before it is planned for again, by default. */
    public static final double DEFAULT_GOAL_MOVED = 1.5d;

    /** Ticks between replaying the rest of a route through the world, by default. */
    public static final int DEFAULT_RECHECK_TICKS = 10;

    /** Ticks without getting closer before reporting stuck, by default. */
    public static final int DEFAULT_STUCK_TICKS = 100;

    /** Blocks ahead along a coarse route handed to the local planner at a time, by default. */
    public static final double DEFAULT_SECTION_REACH = 16d;

    /** Blocks from a coarse waypoint that count as having passed it. */
    private static final double WAYPOINT_REACHED = 0.75d;

    /** Blocks the remaining distance must fall by to count as getting closer. */
    private static final double CLOSER_BY = 0.25d;

    /** Replans for drift in a row, each within a couple of ticks of the last, before reporting stuck. */
    private static final int DRIFT_STREAK_STUCK = 5;

    private final PathProvider provider;
    private final PathProvider local;
    private final SimulationService simulation;
    private final ControlService controls;
    private final RotationService rotations;
    private final IntSupplier priority;
    private final RotationRequest turn;
    private final DoubleSupplier drift;
    private final DoubleSupplier goalMoved;
    private final IntSupplier recheckTicks;
    private final IntSupplier stuckTicks;
    private final IntSupplier replanEvery;
    private final DoubleSupplier sectionReach;
    private final BooleanSupplier steerSprint;
    private final BooleanSupplier turnHead;

    // ---- the trip ------------------------------------------------------------

    private Goal goal;
    private Progress failure;
    private Progress progress = Progress.failed("not travelling");

    /** The provider's route. */
    private Route route;
    private Vec3 anchorAtPlan;

    /** The precise route being followed: the provider's own, or a section of a coarse one. */
    private Route section;
    private Goal sectionGoal;
    private int index;
    private int sinceCheck;
    private int sincePlan;

    /** The next waypoint of a coarse route not yet passed. */
    private int waypoint;

    private double bestRemaining;
    private int sinceCloser;
    private int driftStreak;

    // ---- stats ---------------------------------------------------------------

    private int plans;
    private int followedTicks;
    private int steeredTicks;
    private Replan lastReplan;
    private double worstDrift;
    private final Map<Replan, Integer> counts = new EnumMap<>(Replan.class);

    private Navigator(Builder builder) {
        this.provider = builder.provider;
        this.local = builder.local;
        this.simulation = builder.simulation;
        this.controls = builder.controls;
        this.rotations = builder.rotations;
        this.priority = builder.priority;
        this.turn = builder.turn;
        this.drift = builder.drift;
        this.goalMoved = builder.goalMoved;
        this.recheckTicks = builder.recheckTicks;
        this.stuckTicks = builder.stuckTicks;
        this.replanEvery = builder.replanEvery;
        this.sectionReach = builder.sectionReach;
        this.steerSprint = builder.steerSprint;
        this.turnHead = builder.turnHead;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------ the trip

    /**
     * Sets off for {@code goal}, replacing any trip under way.
     *
     * <p>Nothing is planned until the next {@link #tick}, which has the player's
     * state to plan from.
     */
    public void travel(Goal goal) {
        Validate.notNull(goal, "goal");
        clearTrip();
        this.goal = goal;
        this.progress = Progress.running(Double.NaN);
        plans = 0;
        followedTicks = 0;
        steeredTicks = 0;
        lastReplan = null;
        worstDrift = 0d;
        counts.clear();
    }

    /** Ends the trip: a driving provider is cancelled and the controls are let go. */
    public void stop() {
        if (goal != null && provider.drives()) {
            provider.cancel();
        }
        clearTrip();
        goal = null;
        progress = Progress.failed("not travelling");
        letGo();
    }

    /**
     * Moves one tick closer to the goal.
     *
     * <p>Call it once a tick, after the tick opens and before the game reads its
     * keys, with the state the tick starts from: a {@code TickEvent} handler at
     * {@code Stage.PRE} is the place. Its claims last this tick only, so filed
     * after the keys were read &mdash; from the motion event, which comes after
     * the player has moved &mdash; they lapse before they are ever applied.
     *
     * @param player the player's position, velocity and footing now
     * @return how the trip is going
     */
    public Progress tick(MotionState player) {
        Validate.notNull(player, "player");
        if (goal == null) {
            return progress;
        }
        if (failure != null) {
            return failure;
        }
        if (provider.drives()) {
            Progress driven = provider.follow(goal);
            progress = driven != null ? driven : Progress.failed(name(provider) + " reported nothing");
            return progress;
        }

        Vec3 feet = player.getPosition();
        if (goal.isMet(feet)) {
            clearTrip();
            letGo();
            progress = Progress.arrived();
            return progress;
        }

        sincePlan++;
        Replan why = null;
        if (route == null) {
            why = Replan.START;
        } else if (moved(goal, anchorAtPlan)) {
            why = Replan.GOAL_MOVED;
        } else if (replanEvery.getAsInt() > 0 && sincePlan >= replanEvery.getAsInt()) {
            why = Replan.REFRESH;
        }
        if (why != null && !planRoute(player, why)) {
            return progress;
        }

        if (route.isPrecise()) {
            Replan off = check(player);
            if (off != null && !planRoute(player, off)) {
                return progress;
            }
            follow();
        } else if (local != null) {
            if (!followCoarse(player)) {
                return progress;
            }
        } else if (!steer(player)) {
            return progress;
        }
        progress = measure(feet);
        return progress;
    }

    // ------------------------------------------------------------- reading

    /** @return the goal being travelled to, or null */
    public Goal getGoal() {
        return goal;
    }

    /** @return whether there is a goal and the trip has not failed */
    public boolean isTravelling() {
        return goal != null && failure == null;
    }

    /** @return the last tick's progress */
    public Progress getProgress() {
        return progress;
    }

    /** @return the provider's route, or null */
    public Route getRoute() {
        return route;
    }

    /** @return the precise route being followed key for key, or null: for drawing where the player is about to go */
    public Route getSection() {
        return section;
    }

    /** @return the tick of {@link #getSection()} the next keys come from */
    public int getSectionTick() {
        return index;
    }

    public NavigatorStats getStats() {
        return new NavigatorStats(plans, followedTicks, steeredTicks, lastReplan, worstDrift, counts);
    }

    // ------------------------------------------------------------ planning

    /** Asks the provider for a route. @return false when there is none, having failed the trip */
    private boolean planRoute(MotionState player, Replan why) {
        count(why);
        Route planned = provider.plan(goal, player);
        if (planned == null) {
            return fail(name(provider) + " found no way to " + goal + reasonOf(provider), player);
        }
        if (planned.isPrecise() && planned.getTicks() == 0) {
            return fail(name(provider) + " planned a route with no ticks to " + goal, player);
        }
        route = planned;
        anchorAtPlan = goal.anchor();
        waypoint = 0;
        if (planned.isPrecise()) {
            begin(planned, goal);
        } else {
            section = null;
            sectionGoal = null;
        }
        return true;
    }

    /** Asks the local planner for the next stretch. @return false when there is none, having failed the trip */
    private boolean planSection(MotionState player, Replan why) {
        count(why);
        Goal stretch = nextSectionGoal(player.getPosition());
        Route planned = local.plan(stretch, player);
        if (planned == null) {
            return fail(name(local) + " found no way along the route to " + stretch + reasonOf(local), player);
        }
        if (!planned.isPrecise() || planned.getTicks() == 0) {
            return fail(name(local) + " did not plan a precise route to " + stretch, player);
        }
        begin(planned, stretch);
        return true;
    }

    private void begin(Route precise, Goal toward) {
        section = precise;
        sectionGoal = toward;
        index = 0;
        sinceCheck = 0;
        sincePlan = 0;
    }

    /**
     * @return why the precise route being followed should be given up, or null
     *         to carry on
     */
    private Replan check(MotionState player) {
        if (section == null) {
            return Replan.START;
        }
        MotionState expected = index == 0 ? section.getStart() : section.stateAt(index - 1);
        double off = expected.getPosition().distanceTo(player.getPosition());
        if (off > drift.getAsDouble()) {
            worstDrift = Math.max(worstDrift, off);
            driftStreak = sincePlan <= 2 ? driftStreak + 1 : 1;
            return Replan.DRIFT;
        }
        if (sincePlan > 2) {
            driftStreak = 0;
        }
        if (index >= section.getTicks()) {
            return Replan.ROUTE_ENDED;
        }
        int every = recheckTicks.getAsInt();
        if (every > 0 && ++sinceCheck >= every) {
            sinceCheck = 0;
            if (!stillLands(expected)) {
                return Replan.BLOCKS_CHANGED;
            }
        }
        return null;
    }

    /** @return whether the rest of the route, played through the world as it is now, still ends where planned */
    private boolean stillLands(MotionState expected) {
        MotionState state = expected;
        for (int tick = index; tick < section.getTicks(); tick++) {
            state = simulation.step(state, section.inputAt(tick));
        }
        MotionState planned = section.stateAt(section.getTicks() - 1);
        return state.getPosition().distanceTo(planned.getPosition()) <= drift.getAsDouble();
    }

    // ----------------------------------------------------------- following

    private void follow() {
        MovementInput input = section.inputAt(index++);
        followedTicks++;
        claim(input);
    }

    /** Follows a coarse route a stretch at a time. @return false if the trip failed */
    private boolean followCoarse(MotionState player) {
        Vec3 feet = player.getPosition();
        List<Vec3> waypoints = route.getWaypoints();
        while (waypoint < waypoints.size() && reached(waypoints.get(waypoint), feet)) {
            waypoint++;
        }
        if (waypoint >= waypoints.size() && !route.isComplete()) {
            if (!planRoute(player, Replan.ROUTE_ENDED)) {
                return false;
            }
            if (route.isPrecise()) {
                follow();
                return true;
            }
        }

        Replan why;
        if (section == null) {
            why = Replan.SECTION;
        } else {
            // A section's route ends on the tick its goal is met, so a section done
            // is a route ended, and the next stretch begins.
            why = check(player);
            if (why == Replan.ROUTE_ENDED) {
                why = Replan.SECTION;
            }
        }
        if (why != null && !planSection(player, why)) {
            return false;
        }
        follow();
        return true;
    }

    /**
     * @return the goal for the next stretch of a coarse route: as far along it as
     *         stays within reach, or the trip's goal itself once that is the end
     */
    private Goal nextSectionGoal(Vec3 feet) {
        List<Vec3> waypoints = route.getWaypoints();
        if (waypoints.isEmpty()) {
            return goal;
        }
        double reach = sectionReach.getAsDouble();
        int last = Math.min(waypoint, waypoints.size() - 1);
        while (last + 1 < waypoints.size() && waypoints.get(last + 1).distanceTo(feet) <= reach) {
            last++;
        }
        if (last == waypoints.size() - 1 && route.isComplete()) {
            return goal;
        }
        return Goal.near(waypoints.get(last), WAYPOINT_REACHED);
    }

    /** Steers along a coarse route with no local planner. @return false if the trip failed */
    private boolean steer(MotionState player) {
        Vec3 feet = player.getPosition();
        List<Vec3> waypoints = route.getWaypoints();
        while (waypoint < waypoints.size() && reached(waypoints.get(waypoint), feet)) {
            waypoint++;
        }
        Vec3 target;
        if (waypoint < waypoints.size()) {
            target = waypoints.get(waypoint);
        } else if (!route.isComplete()) {
            if (!planRoute(player, Replan.ROUTE_ENDED)) {
                return false;
            }
            if (route.isPrecise()) {
                follow();
                return true;
            }
            return steer(player);
        } else {
            target = goal.anchor();
            if (target == null) {
                return fail("the route ended short of " + goal + " and it has no point to steer to", player);
            }
        }
        double dx = target.getX() - feet.getX();
        double dz = target.getZ() - feet.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        boolean climb = player.isOnGround()
                && target.getY() - feet.getY() > simulation.getProfile().getStepHeight();
        claim(MovementInput.forward(yaw).withSprint(steerSprint.getAsBoolean()).withJump(climb));
        steeredTicks++;
        return true;
    }

    private void claim(MovementInput input) {
        int claimPriority = priority.getAsInt();
        controls.move(this, input, claimPriority);
        if (rotations == null) {
            return;
        }
        if (turnHead.getAsBoolean()) {
            float pitch = rotations.getRotation().getPitch();
            rotations.request(this, turn.retarget(Vec2.rotation(input.getYaw(), pitch)).priority(claimPriority));
        } else {
            rotations.release(this);
        }
    }

    // ------------------------------------------------------------- progress

    private Progress measure(Vec3 feet) {
        double remaining = goal.gap(feet).distance();
        if (remaining < bestRemaining - CLOSER_BY) {
            bestRemaining = remaining;
            sinceCloser = 0;
        } else {
            sinceCloser++;
        }
        if (driftStreak >= DRIFT_STREAK_STUCK) {
            return Progress.stuck("the player keeps moving differently from the simulation: planned again "
                    + driftStreak + " times in a row for drift (water, a ladder, or the profile not matching"
                    + " the game?)", remaining);
        }
        int stuck = stuckTicks.getAsInt();
        if (stuck > 0 && sinceCloser >= stuck) {
            return Progress.stuck(String.format("no closer to %s in %d ticks (%.1f blocks left at best)",
                    goal, sinceCloser, bestRemaining), remaining);
        }
        return Progress.running(remaining);
    }

    // ----------------------------------------------------------- internals

    private boolean fail(String reason, MotionState player) {
        failure = Progress.failed(reason, goal.gap(player.getPosition()).distance());
        progress = failure;
        letGo();
        return false;
    }

    private void clearTrip() {
        failure = null;
        route = null;
        anchorAtPlan = null;
        section = null;
        sectionGoal = null;
        index = 0;
        sinceCheck = 0;
        sincePlan = 0;
        waypoint = 0;
        bestRemaining = Double.POSITIVE_INFINITY;
        sinceCloser = 0;
        driftStreak = 0;
    }

    private void letGo() {
        controls.release(this);
        if (rotations != null) {
            rotations.release(this);
        }
    }

    private void count(Replan why) {
        plans++;
        lastReplan = why;
        Integer count = counts.get(why);
        counts.put(why, count == null ? 1 : count + 1);
    }

    private boolean moved(Goal goal, Vec3 before) {
        if (before == null) {
            return false;
        }
        Vec3 now = goal.anchor();
        return now != null && now.distanceTo(before) > goalMoved.getAsDouble();
    }

    private static boolean reached(Vec3 waypoint, Vec3 feet) {
        return waypoint.horizontalDistanceTo(feet) <= WAYPOINT_REACHED && Math.abs(waypoint.getY() - feet.getY()) < 1d;
    }

    private static String name(PathProvider provider) {
        return provider.getClass().getSimpleName();
    }

    private static String reasonOf(PathProvider provider) {
        return provider instanceof LocalPlanner ? ": " + ((LocalPlanner) provider).getLastStats().getReason() : "";
    }

    /**
     * Builds a {@link Navigator}.
     *
     * <p>Needs a provider, the controls to claim and the simulation it checks
     * routes with. Claiming the rotation is optional but strongly advised: without
     * it the keys are meant for a yaw the player may not be facing, and your
     * {@link dev.px.core.control.MovementSink} has to correct for it.
     */
    public static final class Builder {

        private PathProvider provider;
        private PathProvider local;
        private SimulationService simulation;
        private ControlService controls;
        private RotationService rotations;
        private IntSupplier priority = () -> RotationPriority.NORMAL;
        private RotationRequest turn = RotationRequest.at(Vec2.ZERO);
        private DoubleSupplier drift = () -> DEFAULT_DRIFT;
        private DoubleSupplier goalMoved = () -> DEFAULT_GOAL_MOVED;
        private IntSupplier recheckTicks = () -> DEFAULT_RECHECK_TICKS;
        private IntSupplier stuckTicks = () -> DEFAULT_STUCK_TICKS;
        private IntSupplier replanEvery = () -> 0;
        private DoubleSupplier sectionReach = () -> DEFAULT_SECTION_REACH;
        private BooleanSupplier steerSprint = () -> true;
        private BooleanSupplier turnHead = () -> true;

        private Builder() {
        }

        /** Where routes come from: a planner, a long-range pathfinder, or one that drives. */
        public Builder provider(PathProvider provider) {
            this.provider = Validate.notNull(provider, "provider");
            return this;
        }

        /**
         * The planner that turns a coarse route into precise keys a stretch at a
         * time, usually a {@link LocalPlanner}. Without one, a coarse route is
         * steered along.
         */
        public Builder local(PathProvider local) {
            this.local = Validate.notNull(local, "local");
            return this;
        }

        /** The rules and world routes are checked against. Usually {@code Core.simulation()}. */
        public Builder simulation(SimulationService simulation) {
            this.simulation = Validate.notNull(simulation, "simulation");
            return this;
        }

        /** Where the movement keys are claimed. Usually {@code Core.controls()}. */
        public Builder controls(ControlService controls) {
            this.controls = Validate.notNull(controls, "controls");
            return this;
        }

        /** Where the yaw each route's keys are meant for is claimed. Usually {@code Core.rotations()}. */
        public Builder rotations(RotationService rotations) {
            this.rotations = Validate.notNull(rotations, "rotations");
            return this;
        }

        /** The priority of every claim, read each tick. */
        public Builder priority(IntSupplier priority) {
            this.priority = Validate.notNull(priority, "priority");
            return this;
        }

        public Builder priority(int priority) {
            return priority(() -> priority);
        }

        /**
         * How the head turns to each yaw: a {@link RotationRequest} whose step,
         * hold and mode are kept and whose target and priority are replaced. Snaps
         * by default. A turn slower than the route's is still followed, a little
         * off, and planned again when it drifts.
         */
        public Builder turn(RotationRequest template) {
            this.turn = Validate.notNull(template, "template");
            return this;
        }

        /** Blocks the player may be from where the route expected before it is planned again, read each tick. */
        public Builder drift(DoubleSupplier blocks) {
            this.drift = Validate.notNull(blocks, "blocks");
            return this;
        }

        public Builder drift(double blocks) {
            Validate.check(blocks > 0d, "drift must be above zero, got " + blocks);
            return drift(() -> blocks);
        }

        /** Blocks a goal that follows something may move before it is planned for again, read each tick. */
        public Builder goalMoved(DoubleSupplier blocks) {
            this.goalMoved = Validate.notNull(blocks, "blocks");
            return this;
        }

        public Builder goalMoved(double blocks) {
            Validate.check(blocks > 0d, "goalMoved must be above zero, got " + blocks);
            return goalMoved(() -> blocks);
        }

        /** Ticks between replaying the rest of the route through the world; 0 never does. */
        public Builder recheckTicks(IntSupplier ticks) {
            this.recheckTicks = Validate.notNull(ticks, "ticks");
            return this;
        }

        public Builder recheckTicks(int ticks) {
            Validate.check(ticks >= 0, "recheckTicks must not be negative, got " + ticks);
            return recheckTicks(() -> ticks);
        }

        /** Ticks without getting closer before reporting {@link Progress.State#STUCK}; 0 never does. */
        public Builder stuckTicks(IntSupplier ticks) {
            this.stuckTicks = Validate.notNull(ticks, "ticks");
            return this;
        }

        public Builder stuckTicks(int ticks) {
            Validate.check(ticks >= 0, "stuckTicks must not be negative, got " + ticks);
            return stuckTicks(() -> ticks);
        }

        /**
         * Plans again every this many ticks however the route is going; 0, the
         * default, never does. For a {@link dev.px.navigation.danger.Danger} that
         * moves: a plan weighs hostiles where they were predicted to be when it was
         * made.
         */
        public Builder replanEvery(IntSupplier ticks) {
            this.replanEvery = Validate.notNull(ticks, "ticks");
            return this;
        }

        public Builder replanEvery(int ticks) {
            Validate.check(ticks >= 0, "replanEvery must not be negative, got " + ticks);
            return replanEvery(() -> ticks);
        }

        /** Blocks along a coarse route handed to the local planner at a time; keep it inside its range. */
        public Builder sectionReach(DoubleSupplier blocks) {
            this.sectionReach = Validate.notNull(blocks, "blocks");
            return this;
        }

        public Builder sectionReach(double blocks) {
            Validate.check(blocks > 0d, "sectionReach must be above zero, got " + blocks);
            return sectionReach(() -> blocks);
        }

        /** Whether steering along a coarse route sprints, read each tick. Yours: sprinting needs what the game says it needs. */
        public Builder steerSprint(BooleanSupplier sprint) {
            this.steerSprint = Validate.notNull(sprint, "sprint");
            return this;
        }

        /**
         * Whether the head is turned to the yaw each tick's keys are meant for,
         * read each tick. On by default. Off, only the keys are claimed and the head
         * is left to someone else &mdash; a fight aiming at a target, say &mdash;
         * and your {@link dev.px.core.control.MovementSink} corrects the keys to the
         * yaw the player faces. Corrected keys are a little less exact than the
         * route's, so expect a few more plans for drift.
         */
        public Builder turnHead(BooleanSupplier turn) {
            this.turnHead = Validate.notNull(turn, "turn");
            return this;
        }

        public Builder turnHead(boolean turn) {
            return turnHead(() -> turn);
        }

        public Navigator build() {
            StringBuilder missing = new StringBuilder();
            if (provider == null) {
                missing.append(", provider");
            }
            if (controls == null) {
                missing.append(", controls");
            }
            if (simulation == null) {
                missing.append(", simulation");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("a Navigator needs: " + missing.substring(2));
            }
            return new Navigator(this);
        }
    }
}
