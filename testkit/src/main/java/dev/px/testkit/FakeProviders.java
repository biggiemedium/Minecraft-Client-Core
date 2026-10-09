package dev.px.testkit;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.PathProvider;
import dev.px.core.navigation.Progress;
import dev.px.core.navigation.Route;
import dev.px.core.util.Validate;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;

/**
 * Pathfinders that answer the way a test needs them to, for testing what is
 * built on top of one without testing the pathfinder too.
 *
 * <pre>{@code
 * FakeProviders.Planner line = FakeProviders.straightLine();        // a coarse route straight at the goal
 * FakeProviders.Planner none = FakeProviders.unreachable();         // never a route
 * FakeProviders.Planner fixed = FakeProviders.routes(first, second); // these, in order, then none
 * FakeProviders.Driver baritone = FakeProviders.driver(sandbox.getPlayer(), 0.3);
 *
 * line.getPlans();      // how often it was asked
 * }</pre>
 */
public final class FakeProviders {

    private FakeProviders() {
    }

    /** @return a planner that answers every goal with a coarse route of one waypoint, the goal's anchor */
    public static Planner straightLine() {
        return new Planner(null, true);
    }

    /** @return a planner that never finds a route */
    public static Planner unreachable() {
        return new Planner(new ArrayDeque<Route>(), false);
    }

    /** @return a planner that hands out {@code routes} in order, then none */
    public static Planner routes(Route... routes) {
        Validate.notNull(routes, "routes");
        return new Planner(new ArrayDeque<>(Arrays.asList(routes)), false);
    }

    /**
     * @return a provider that drives: each tick it is followed, it moves
     *         {@code player} up to {@code blocksPerTick} straight at the goal's
     *         anchor, through anything, as a pathfinder that moves the player
     *         itself would
     */
    public static Driver driver(SimPlayer player, double blocksPerTick) {
        Validate.notNull(player, "player");
        Validate.check(blocksPerTick > 0d, "speed must be above zero, got " + blocksPerTick);
        return new Driver(player, blocksPerTick);
    }

    /** A provider that only plans, counting how often it is asked. */
    public static final class Planner implements PathProvider {

        private final Deque<Route> routes;
        private final boolean straight;
        private int plans;

        private Planner(Deque<Route> routes, boolean straight) {
            this.routes = routes;
            this.straight = straight;
        }

        @Override
        public Route plan(Goal goal, MotionState from) {
            plans++;
            if (straight) {
                Vec3 anchor = goal.anchor();
                return anchor == null ? null : Route.coarse(goal, Collections.singletonList(anchor), true);
            }
            return routes.isEmpty() ? null : routes.poll();
        }

        public int getPlans() {
            return plans;
        }
    }

    /** A provider that drives, counting how often it is followed and cancelled. */
    public static final class Driver implements PathProvider {

        private final SimPlayer player;
        private final double speed;
        private int follows;
        private int cancels;
        private Goal following;

        private Driver(SimPlayer player, double speed) {
            this.player = player;
            this.speed = speed;
        }

        @Override
        public Route plan(Goal goal, MotionState from) {
            return null;
        }

        @Override
        public boolean drives() {
            return true;
        }

        @Override
        public Progress follow(Goal goal) {
            follows++;
            following = goal;
            Vec3 at = player.getPosition();
            if (goal.isMet(at)) {
                return Progress.arrived();
            }
            Vec3 anchor = goal.anchor();
            if (anchor == null) {
                return Progress.failed("the fake driver can only drive to a goal with an anchor");
            }
            Vec3 way = anchor.subtract(at);
            double length = way.length();
            player.teleport(length <= speed ? anchor : at.add(way.scale(speed / length)));
            return goal.isMet(player.getPosition()) ? Progress.arrived()
                    : Progress.running(goal.gap(player.getPosition()).distance());
        }

        @Override
        public void cancel() {
            cancels++;
            following = null;
        }

        public int getFollows() {
            return follows;
        }

        public int getCancels() {
            return cancels;
        }

        /** @return the goal it was last asked to follow, or null once cancelled */
        public Goal getFollowing() {
            return following;
        }
    }
}
