package dev.px.navigation;

import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * How a {@link Navigator}'s trip has gone: how often it planned and why.
 *
 * <pre>{@code
 * NavigatorStats stats = navigator.getStats();
 * if (stats.count(Replan.DRIFT) > 20) {
 *     // the game keeps doing something the simulation does not: water, a ladder, a server's own rules
 * }
 * }</pre>
 *
 * <p>Counts from the last {@link Navigator#travel}. Immutable: a snapshot.
 */
@Getter
public final class NavigatorStats {

    /** Every route asked for, from the provider and from the local planner. */
    private final int plans;
    /** Ticks a precise route's keys were held. */
    private final int followedTicks;
    /** Ticks spent steering along a coarse route with no local planner. */
    private final int steeredTicks;
    /** Why the last route was asked for; null before the first. */
    private final Replan lastReplan;
    /** The furthest the player was from where a route expected, in blocks, when it was given up. */
    private final double worstDrift;
    private final Map<Replan, Integer> counts;

    NavigatorStats(int plans, int followedTicks, int steeredTicks, Replan lastReplan, double worstDrift,
                   Map<Replan, Integer> counts) {
        this.plans = plans;
        this.followedTicks = followedTicks;
        this.steeredTicks = steeredTicks;
        this.lastReplan = lastReplan;
        this.worstDrift = worstDrift;
        this.counts = Collections.unmodifiableMap(new EnumMap<>(counts));
    }

    /** @return how many routes were asked for for {@code reason} */
    public int count(Replan reason) {
        Integer count = counts.get(reason);
        return count == null ? 0 : count;
    }

    @Override
    public String toString() {
        return "NavigatorStats(" + plans + " plans " + counts + ", " + followedTicks + " followed, "
                + steeredTicks + " steered, worst drift " + String.format("%.3f", worstDrift) + ")";
    }
}
