package dev.px.core.util.spatial;

import dev.px.core.math.Vec3i;

/**
 * What {@link AStar} is allowed to ask about the world.
 *
 * <p>This interface is the whole reason a pathfinder can live in Core. The search
 * is arithmetic over a grid and belongs here; knowing whether a cell holds a
 * fence gate, a cobweb, or lava is the adapter's business and belongs there. Two
 * methods is the entire contract between them.
 *
 * <p>The same seam pays off twice over: the search can be tested against a hand
 * drawn grid with no game running, which is how the tests in this project cover
 * it, and a caller can path through something that is not the world at all
 * &mdash; a predicted future state, a region with hazards weighted against.
 *
 * <p><b>Passable means occupiable, not empty.</b> For a walking agent that
 * includes having a floor underneath and headroom above; for a flying one it does
 * not. Encoding that here rather than in the search is what keeps one pathfinder
 * usable for both.
 *
 * <p><b>Keep both methods O(1).</b> A search calls {@link #isPassable} up to
 * about ten times per expanded node, and again per vertical candidate, so a
 * {@code maxNodes} of 4000 can mean tens of thousands of calls. A lookup that
 * hits a chunk cache is fine; one that allocates or searches is the search's
 * running time.
 *
 * <pre>{@code
 * PathSpace walkable = position -> {
 *     BlockPos pos = adapt(position);
 *     return world.isAir(pos) && world.isAir(pos.up()) && world.isSolid(pos.down());
 * };
 * }</pre>
 */
@FunctionalInterface
public interface PathSpace {

    /** @return whether the agent can occupy this cell. */
    boolean isPassable(Vec3i position);

    /**
     * @return a multiplier on the cost of entering this cell, 1 being ordinary
     *
     * <p>How a route is steered without being forbidden: weight cells near lava at
     * 10 and the path goes around unless going around is ten times longer. Values
     * below 1 make a cell preferred, which is useful for roads and dangerous for
     * the search &mdash; A* only guarantees the shortest path while the heuristic
     * never overestimates, and a cost under 1 can break that. Prefer weighting
     * everything else up.
     */
    default double costOf(Vec3i position) {
        return 1d;
    }
}
