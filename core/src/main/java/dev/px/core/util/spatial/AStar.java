package dev.px.core.util.spatial;

import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the block grid, told what is solid by a {@link PathSpace}.
 *
 * <p>Core has no world, so this searches an abstraction of one. Everything the
 * search needs to know arrives through two methods on an interface the adapter
 * implements, which means the algorithm compiles with no game on the classpath
 * and is tested here against grids drawn in a test file.
 *
 * <p>Three things make this usable from a client rather than only correct:
 *
 * <ul>
 *   <li><b>A node budget.</b> A pathfinder asked for an unreachable goal explores
 *       everything it can reach before admitting defeat, and on the render thread
 *       that is a freeze. {@link #maxNodes} caps the work.</li>
 *   <li><b>A best-effort result.</b> Out of budget, the search returns the route
 *       to the closest point it found rather than nothing, because moving closer
 *       and searching again is how a long path gets walked in practice.</li>
 *   <li><b>Vertical rules as parameters.</b> How far the agent can step up and
 *       drop down is movement policy, not geometry, so it is configured rather
 *       than assumed.</li>
 * </ul>
 *
 * <pre>{@code
 * Path path = AStar.in(walkable)
 *         .diagonal(true)
 *         .stepUp(1)
 *         .maxFall(3)
 *         .maxNodes(4000)
 *         .find(from, to);
 *
 * if (!path.isEmpty()) {
 *     follow(path.toPositions());
 * }
 * }</pre>
 *
 * <p><b>Complexity.</b> O(N log N) time and O(N) memory, for N nodes expanded and
 * a branching factor of at most ten. N is bounded by {@link #maxNodes}, which is
 * what turns the worst case from "explores everything reachable" into a number
 * chosen in advance. The {@code log N} is the binary heap; a node reached more
 * cheaply is re-queued rather than decreased in place, so the heap holds at most
 * one entry per relaxation.
 *
 * <pre>
 * f(n) = g(n) + w · h(n)
 * </pre>
 *
 * <p>An instance is configuration, not state: {@link #find} allocates everything
 * it needs and keeps nothing, so one configured searcher can be reused and shared.
 */
public final class AStar {

    /** Cost of a diagonal step, as against 1 for a straight one. */
    private static final double DIAGONAL_COST = Math.sqrt(2d);

    /** The four compass steps, then the four diagonals. */
    private static final int[][] HORIZONTAL = {
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
            { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
    };

    private final PathSpace space;

    private boolean diagonal = true;
    private boolean vertical;
    private int stepUp = 1;
    private int maxFall = 3;
    private int maxNodes = 4000;
    private double heuristicWeight = 1d;

    private AStar(PathSpace space) {
        this.space = Validate.notNull(space, "space");
    }

    public static AStar in(PathSpace space) {
        return new AStar(space);
    }

    // -------------------------------------------------------- configuration

    /** Whether the agent may move diagonally. Corners are never cut. */
    public AStar diagonal(boolean allowed) {
        this.diagonal = allowed;
        return this;
    }

    /**
     * Whether the agent may move straight up and down without moving sideways.
     *
     * <p>Off by default, because a walking agent cannot. Turn it on for flight, a
     * ladder, or anything else that climbs in place.
     */
    public AStar vertical(boolean allowed) {
        this.vertical = allowed;
        return this;
    }

    /** How many cells the agent can rise in one horizontal step. A jump is 1. */
    public AStar stepUp(int blocks) {
        Validate.check(blocks >= 0, "stepUp must not be negative");
        this.stepUp = blocks;
        return this;
    }

    /** How far the agent will drop in one step. Beyond this the path goes around. */
    public AStar maxFall(int blocks) {
        Validate.check(blocks >= 0, "maxFall must not be negative");
        this.maxFall = blocks;
        return this;
    }

    /**
     * The most cells to expand before giving up and returning the best route so far.
     *
     * <p>The number to turn down if pathing ever costs a visible frame. A few
     * thousand is a long way in an open world and almost nothing in a maze.
     */
    public AStar maxNodes(int nodes) {
        Validate.check(nodes > 0, "maxNodes must be positive");
        this.maxNodes = nodes;
        return this;
    }

    /**
     * Scales the estimate of remaining distance. 1 is exact.
     *
     * <p>The {@code w} in {@code f = g + w·h}. A path found with weight w is at
     * worst w times longer than the shortest one, which is the guarantee being
     * traded away.
     *
     * <p>Above 1 the search leans harder towards the goal: it finishes sooner and
     * explores less, and the path it returns may be longer than the shortest one.
     * That trade is usually worth making in a game &mdash; 1.2 or so cuts the work
     * substantially for a path nobody can tell apart &mdash; but it is a trade,
     * and below 1 there is nothing to gain: the result is identical and slower.
     */
    public AStar heuristicWeight(double weight) {
        Validate.check(weight > 0d, "heuristicWeight must be positive");
        this.heuristicWeight = weight;
        return this;
    }

    // --------------------------------------------------------------- search

    /**
     * @return the route from {@code start} to {@code goal}
     *
     * <p>{@code start} is assumed occupiable whether or not the space says so: the
     * agent is already standing there, and refusing to path out of a cell the
     * predicate dislikes is how a pathfinder gets stuck in a doorway.
     */
    public Path find(Vec3i start, Vec3i goal) {
        Validate.notNull(start, "start");
        Validate.notNull(goal, "goal");
        if (start.equals(goal)) {
            return Path.of(Collections.singletonList(start), true, 0);
        }

        Map<Long, Node> known = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((left, right) -> Double.compare(left.f, right.f));

        Node origin = new Node(start.getX(), start.getY(), start.getZ(), null);
        origin.g = 0d;
        origin.h = heuristic(start, goal);
        origin.f = origin.h;
        known.put(start.asLong(), origin);
        open.add(origin);

        // The closest node seen, so an exhausted search still has something useful
        // to return rather than nothing.
        Node closest = origin;
        int expanded = 0;

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.closed) {
                // A stale copy left behind when this node was reached more cheaply.
                continue;
            }
            current.closed = true;
            expanded++;

            if (current.x == goal.getX() && current.y == goal.getY() && current.z == goal.getZ()) {
                return reconstruct(current, true, expanded);
            }
            if (current.h < closest.h) {
                closest = current;
            }
            if (expanded >= maxNodes) {
                return reconstruct(closest, false, expanded);
            }

            for (Vec3i neighbour : neighbours(current)) {
                long key = neighbour.asLong();
                Node existing = known.get(key);
                if (existing != null && existing.closed) {
                    continue;
                }
                double step = stepCost(current, neighbour);
                double tentative = current.g + step;
                if (existing != null && tentative >= existing.g) {
                    continue;
                }
                Node node = existing == null
                        ? new Node(neighbour.getX(), neighbour.getY(), neighbour.getZ(), current)
                        : existing;
                node.parent = current;
                node.g = tentative;
                node.h = heuristic(neighbour, goal);
                node.f = tentative + node.h * heuristicWeight;
                known.put(key, node);
                // No decrease-key on a PriorityQueue: re-add and let the closed
                // flag discard whichever copy surfaces second.
                open.add(node);
            }
        }
        return reconstruct(closest, false, expanded);
    }

    // ------------------------------------------------------------ expansion

    /**
     * @return the cells reachable in one move from {@code node}
     *
     * <p>At most {@code 8 + 2} candidates, each costing up to
     * {@code 1 + stepUp + maxFall} passability tests.
     *
     * <p>For each horizontal direction the agent lands wherever the column allows:
     * level first, then up to {@link #stepUp}, then down as far as
     * {@link #maxFall}. Preferring the level landing keeps a path from hopping over
     * ground it could have walked along.
     */
    private List<Vec3i> neighbours(Node node) {
        List<Vec3i> found = new ArrayList<>(diagonal ? 10 : 6);
        int directions = diagonal ? HORIZONTAL.length : 4;
        for (int i = 0; i < directions; i++) {
            int dx = HORIZONTAL[i][0];
            int dz = HORIZONTAL[i][1];
            Vec3i landing = landing(node, dx, dz);
            if (landing == null) {
                continue;
            }
            // A diagonal move clips two corners; both have to be open, or the agent
            // walks through the join between two blocks.
            if (dx != 0 && dz != 0
                    && (landing(node, dx, 0) == null || landing(node, 0, dz) == null)) {
                continue;
            }
            found.add(landing);
        }
        if (vertical) {
            Vec3i above = Vec3i.of(node.x, node.y + 1, node.z);
            if (space.isPassable(above)) {
                found.add(above);
            }
            Vec3i below = Vec3i.of(node.x, node.y - 1, node.z);
            if (space.isPassable(below)) {
                found.add(below);
            }
        }
        return found;
    }

    /** @return where the agent ends up stepping one cell in this direction, or null. */
    private Vec3i landing(Node node, int dx, int dz) {
        int x = node.x + dx;
        int z = node.z + dz;
        Vec3i level = Vec3i.of(x, node.y, z);
        if (space.isPassable(level)) {
            return level;
        }
        for (int rise = 1; rise <= stepUp; rise++) {
            Vec3i up = Vec3i.of(x, node.y + rise, z);
            if (space.isPassable(up)) {
                return up;
            }
        }
        for (int drop = 1; drop <= maxFall; drop++) {
            Vec3i down = Vec3i.of(x, node.y - drop, z);
            if (space.isPassable(down)) {
                return down;
            }
        }
        return null;
    }

    /**
     * @return the cost of one move: {@code (diagonal ? √2 : 1) + |Δy|}, scaled by
     *         {@link PathSpace#costOf}
     */
    private double stepCost(Node from, Vec3i to) {
        boolean isDiagonal = from.x != to.getX() && from.z != to.getZ();
        double base = isDiagonal ? DIAGONAL_COST : 1d;
        // Height changes cost what they are worth: a jump or a drop is a step too.
        base += Math.abs(from.y - to.getY());
        return base * space.costOf(to);
    }

    /**
     * @return an optimistic estimate of the distance left
     *
     * <pre>
     * diagonal: h = (dx + dz) + (√2 - 2)·min(dx, dz) + dy
     * straight: h = dx + dz + dy
     * </pre>
     *
     * <p>Octile when diagonals are allowed, taxicab when they are not. Both are the
     * exact cost across open ground, which is as large as an estimate may be
     * without costing A* its guarantee of finding the shortest route.
     */
    private double heuristic(Vec3i from, Vec3i goal) {
        int dx = Math.abs(from.getX() - goal.getX());
        int dy = Math.abs(from.getY() - goal.getY());
        int dz = Math.abs(from.getZ() - goal.getZ());
        double horizontal = diagonal
                ? (dx + dz) + (DIAGONAL_COST - 2d) * Math.min(dx, dz)
                : dx + dz;
        return horizontal + dy;
    }

    private Path reconstruct(Node end, boolean complete, int expanded) {
        List<Vec3i> nodes = new ArrayList<>();
        for (Node step = end; step != null; step = step.parent) {
            nodes.add(Vec3i.of(step.x, step.y, step.z));
        }
        Collections.reverse(nodes);
        return Path.of(nodes, complete, expanded);
    }

    /** A cell the search has reached, and how. */
    private static final class Node {

        private final int x;
        private final int y;
        private final int z;

        private Node parent;
        private double g;
        private double h;
        private double f;
        private boolean closed;

        private Node(int x, int y, int z, Node parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
        }
    }
}
