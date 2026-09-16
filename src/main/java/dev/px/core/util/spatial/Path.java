package dev.px.core.util.spatial;

import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A route through the grid, and whether it actually got there.
 *
 * <p><b>Complexity.</b> {@link #get}, {@link #first()} and {@link #last()} are
 * O(1); {@link #length()} and {@link #toPositions()} are O(n).
 *
 * <p>Returned rather than a bare list because the interesting part of a
 * pathfinding result is the part a list cannot express. A search that ran out of
 * budget still returns its best effort &mdash; the route towards the closest
 * point it reached &mdash; and walking that is usually the right behaviour: get
 * closer, search again from there. Silently returning it as though it were
 * complete is not, which is what {@link #isComplete()} exists to prevent.
 */
public final class Path implements Iterable<Vec3i> {

    private static final Path EMPTY = new Path(Collections.<Vec3i>emptyList(), false, 0);

    private final List<Vec3i> nodes;
    private final boolean complete;
    private final int nodesVisited;

    private Path(List<Vec3i> nodes, boolean complete, int nodesVisited) {
        this.nodes = nodes;
        this.complete = complete;
        this.nodesVisited = nodesVisited;
    }

    static Path of(List<Vec3i> nodes, boolean complete, int nodesVisited) {
        return new Path(Collections.unmodifiableList(new ArrayList<>(nodes)), complete, nodesVisited);
    }

    /** @return a path that found nothing at all. */
    public static Path empty() {
        return EMPTY;
    }

    /** @return the cells to walk, start first, goal last. */
    public List<Vec3i> getNodes() {
        return nodes;
    }

    /**
     * @return whether the path reaches the goal
     *
     * <p>{@code false} means this is the best route found towards it before the
     * search ran out of nodes, or that the goal is unreachable.
     */
    public boolean isComplete() {
        return complete;
    }

    /** @return how many cells the search expanded. The cost of the answer. */
    public int getNodesVisited() {
        return nodesVisited;
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public Vec3i get(int index) {
        return nodes.get(index);
    }

    public Vec3i first() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    public Vec3i last() {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }

    /** @return the distance walked following every node, in blocks. */
    public double length() {
        double total = 0d;
        for (int i = 1; i < nodes.size(); i++) {
            total += nodes.get(i - 1).distanceTo(nodes.get(i));
        }
        return total;
    }

    /**
     * @return the cell centres, which is what something moving along the path
     *         should actually aim at
     *
     * <p>A cell's own coordinates are its corner. Walking to those puts the agent
     * against the edge of every block on the route and catches it on each one.
     */
    public List<Vec3> toPositions() {
        List<Vec3> positions = new ArrayList<>(nodes.size());
        for (Vec3i node : nodes) {
            positions.add(node.center());
        }
        return positions;
    }

    @Override
    public Iterator<Vec3i> iterator() {
        return nodes.iterator();
    }

    @Override
    public String toString() {
        return "Path[" + nodes.size() + " nodes, "
                + (complete ? "complete" : "partial") + ", " + nodesVisited + " visited]";
    }
}
