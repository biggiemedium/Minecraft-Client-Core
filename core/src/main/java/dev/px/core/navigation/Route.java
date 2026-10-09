package dev.px.core.navigation;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A way to a {@link Goal}, as a {@link PathProvider} planned it.
 *
 * <p>Two kinds, because providers know different amounts:
 *
 * <ul>
 *   <li><b>Coarse</b>: the positions to pass through, and nothing about how.
 *       What a long-range pathfinder gives &mdash; a list of blocks.
 *   <li><b>Precise</b>: the exact keys and yaw for every tick, and the state the
 *       player is expected to be in after each one. What a planner that
 *       simulates every move gives, and what lets whoever follows it notice the
 *       moment the game stops doing what was planned.
 * </ul>
 *
 * <pre>{@code
 * Route route = provider.plan(goal, playerState);
 * if (route != null && route.isPrecise()) {
 *     MovementInput first = route.inputAt(0);        // hold this now
 *     MotionState expected = route.stateAt(0);       // and be here next tick
 * }
 * }</pre>
 *
 * <p>A route need not reach its goal: {@link #isComplete()} is false for one that
 * gets as close as its provider could see, to be followed and then planned on
 * from. Named {@code Route} because Core's grid search already returns a
 * {@link dev.px.core.util.spatial.Path}.
 *
 * <p>Immutable.
 */
public final class Route {

    private final Goal goal;
    private final MotionState start;
    private final List<Vec3> waypoints;
    private final List<MovementInput> inputs;
    private final List<MotionState> states;
    private final boolean complete;

    private Route(Goal goal, MotionState start, List<Vec3> waypoints,
                  List<MovementInput> inputs, List<MotionState> states, boolean complete) {
        this.goal = goal;
        this.start = start;
        this.waypoints = waypoints;
        this.inputs = inputs;
        this.states = states;
        this.complete = complete;
    }

    /**
     * @param waypoints the positions to pass through, feet, in order; the first
     *        is the first to head for, not where the player is
     * @param complete whether the last waypoint meets the goal
     */
    public static Route coarse(Goal goal, List<Vec3> waypoints, boolean complete) {
        Validate.notNull(goal, "goal");
        Validate.notNull(waypoints, "waypoints");
        List<Vec3> copy = new ArrayList<>(waypoints);
        for (Vec3 waypoint : copy) {
            Validate.notNull(waypoint, "waypoint");
        }
        return new Route(goal, null, Collections.unmodifiableList(copy),
                Collections.<MovementInput>emptyList(), Collections.<MotionState>emptyList(), complete);
    }

    /**
     * @param start  the state the route was planned from
     * @param inputs what to hold each tick, in order
     * @param states the state expected after each input: {@code states.get(i)}
     *        follows {@code inputs.get(i)}
     * @param complete whether the last state meets the goal
     */
    public static Route precise(Goal goal, MotionState start, List<MovementInput> inputs,
                                List<MotionState> states, boolean complete) {
        Validate.notNull(goal, "goal");
        Validate.notNull(start, "start");
        Validate.notNull(inputs, "inputs");
        Validate.notNull(states, "states");
        Validate.check(inputs.size() == states.size(),
                "a precise route needs one state per input, got " + inputs.size() + " inputs and "
                        + states.size() + " states");
        List<Vec3> waypoints = new ArrayList<>(states.size());
        for (MotionState state : states) {
            waypoints.add(state.getPosition());
        }
        return new Route(goal, start, Collections.unmodifiableList(waypoints),
                Collections.unmodifiableList(new ArrayList<>(inputs)),
                Collections.unmodifiableList(new ArrayList<>(states)), complete);
    }

    public Goal getGoal() {
        return goal;
    }

    /** @return whether every tick's input and expected state are known */
    public boolean isPrecise() {
        return start != null;
    }

    /** @return whether the route ends where the goal is met */
    public boolean isComplete() {
        return complete;
    }

    /** @return the state a precise route was planned from; null for a coarse one */
    public MotionState getStart() {
        return start;
    }

    /** @return the positions along the route; for a precise one, every tick's */
    public List<Vec3> getWaypoints() {
        return waypoints;
    }

    /** @return every tick's input; empty for a coarse route */
    public List<MovementInput> getInputs() {
        return inputs;
    }

    /** @return the state expected after every tick's input; empty for a coarse route */
    public List<MotionState> getStates() {
        return states;
    }

    /** @return how many ticks a precise route takes; 0 for a coarse one */
    public int getTicks() {
        return inputs.size();
    }

    public MovementInput inputAt(int tick) {
        return inputs.get(tick);
    }

    /** @return the state expected after {@code tick}'s input */
    public MotionState stateAt(int tick) {
        return states.get(tick);
    }

    /** @return where the route ends, or null for an empty one */
    public Vec3 getEnd() {
        return waypoints.isEmpty() ? (start == null ? null : start.getPosition()) : waypoints.get(waypoints.size() - 1);
    }

    @Override
    public String toString() {
        return "Route(" + (isPrecise() ? inputs.size() + " ticks" : waypoints.size() + " waypoints")
                + (complete ? ", complete" : ", partial") + ", to " + goal + ")";
    }
}
