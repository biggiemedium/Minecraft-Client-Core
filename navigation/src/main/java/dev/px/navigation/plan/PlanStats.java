package dev.px.navigation.plan;

import lombok.Getter;

/**
 * What the last plan found and how hard it worked: a route, or why there was none.
 *
 * <pre>{@code
 * Route route = planner.plan(goal, player);
 * if (route == null) {
 *     log(planner.getLastStats().getReason());   // "no route: 1834 moves left the 24-block range, ..."
 * }
 * }</pre>
 *
 * <p>Every move the planner tried and threw away is counted under why, so a
 * plan that finds nothing says whether the goal was out of range, every way
 * there fell too far, or your rule ruled it out.
 *
 * <p>Immutable.
 */
@Getter
public final class PlanStats {

    /** How a plan ended. */
    public enum Outcome {
        /** A route all the way to the goal. */
        FOUND,
        /** A route as close to the goal as the planner could see, to follow and plan on from. */
        PARTIAL,
        /** Nothing: no move got any closer. {@link #getReason()} says what stopped them. */
        NO_ROUTE,
        /** The goal was met where the plan started. */
        ALREADY_THERE
    }

    private final Outcome outcome;
    /** Ticks the route takes; 0 when there is none. */
    private final int routeTicks;
    /** Search states expanded: each one tried every gait at every heading. */
    private final int expanded;
    /** Moves tried: one gait at one heading from one state. */
    private final int moves;
    /** Ticks simulated, across every move tried. */
    private final int simulatedTicks;
    /** Moves that ended somewhere already reached as quickly. */
    private final int duplicates;
    /** Moves that left the planner's range. */
    private final int outOfRange;
    /** Moves that ran past the planner's tick budget. */
    private final int overTime;
    /** Moves your rule refused somewhere along them. */
    private final int refused;
    /** Moves that landed from higher than your drop limit. */
    private final int fellTooFar;
    /** Whether the search stopped because it expanded as many states as it may. */
    private final boolean budgetSpent;
    /** Blocks asked about the adapter's world, each once. */
    private final int worldQueries;
    /** How long the plan took, in microseconds. */
    private final long micros;

    PlanStats(Outcome outcome, int routeTicks, int expanded, int moves, int simulatedTicks, int duplicates,
              int outOfRange, int overTime, int refused, int fellTooFar, boolean budgetSpent,
              int worldQueries, long micros) {
        this.outcome = outcome;
        this.routeTicks = routeTicks;
        this.expanded = expanded;
        this.moves = moves;
        this.simulatedTicks = simulatedTicks;
        this.duplicates = duplicates;
        this.outOfRange = outOfRange;
        this.overTime = overTime;
        this.refused = refused;
        this.fellTooFar = fellTooFar;
        this.budgetSpent = budgetSpent;
        this.worldQueries = worldQueries;
        this.micros = micros;
    }

    static PlanStats none() {
        return new PlanStats(Outcome.NO_ROUTE, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0L);
    }

    /** @return in a sentence, how the plan ended and what was thrown away on the way */
    public String getReason() {
        StringBuilder text = new StringBuilder();
        switch (outcome) {
            case FOUND:
                text.append("found a route of ").append(routeTicks).append(" ticks");
                break;
            case PARTIAL:
                text.append("found a route of ").append(routeTicks).append(" ticks towards the goal, not to it");
                break;
            case ALREADY_THERE:
                return "already there";
            default:
                text.append("no route");
                break;
        }
        text.append(budgetSpent ? " after spending its whole budget of " : " after ")
                .append(expanded).append(" states");
        appendCount(text, outOfRange, "left the range");
        appendCount(text, overTime, "ran out of ticks");
        appendCount(text, refused, "were refused by your rule");
        appendCount(text, fellTooFar, "fell further than your drop limit");
        return text.toString();
    }

    private static void appendCount(StringBuilder text, int count, String what) {
        if (count > 0) {
            text.append("; ").append(count).append(count == 1 ? " move " : " moves ").append(what);
        }
    }

    @Override
    public String toString() {
        return String.format("PlanStats(%s, %d ticks, %d expanded, %d moves, %d simulated, %d duplicates,"
                        + " %d out of range, %d over time, %d refused, %d fell too far%s, %d queries, %dus)",
                outcome, routeTicks, expanded, moves, simulatedTicks, duplicates, outOfRange, overTime,
                refused, fellTooFar, budgetSpent ? ", budget spent" : "", worldQueries, micros);
    }
}
