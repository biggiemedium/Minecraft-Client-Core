package dev.px.core.util.spatial;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds the connected region of cells reachable from a starting point.
 *
 * <p>The shape of several questions a client asks: is this spot actually enclosed,
 * how big is this cave, which cells of this structure are one piece, what can be
 * reached without breaking anything. All of them are one breadth-first walk with
 * a predicate, and all of them are usually written inline with a recursive
 * function that overflows the stack on a large room.
 *
 * <p>Breadth-first, not depth-first, for two reasons: the walk is iterative so
 * nothing recurses, and cells arrive in order of distance from the start, so a
 * caller that only wants the nearest part of a region can stop reading early.
 *
 * <p><b>Complexity.</b> O(n · c) time and O(n) memory, for n cells visited
 * (capped by the budget) and c neighbours per cell &mdash; 6 for
 * {@link Connectivity#FACES}, 26 for {@link Connectivity#ALL}. Each cell is
 * tested once: the visited set is keyed by {@link dev.px.core.math.Vec3i#asLong()},
 * so membership is O(1).
 *
 * <p>Every fill takes a budget, and it is not optional. The predicate defines what
 * is passable, and the first time one is written slightly too permissive the fill
 * discovers it is standing in an open world &mdash; unbounded, on the render
 * thread. {@link Region#isComplete()} reports whether the budget was reached,
 * which is the difference between "this hole is sealed" and "I gave up looking".
 *
 * <pre>{@code
 * Region region = FloodFill.from(start, cell -> world.isAir(cell), 512);
 * boolean sealed = region.isComplete() && region.size() < 64;
 * }</pre>
 */
public final class FloodFill {

    private FloodFill() {
    }

    /** Which neighbours count as connected. */
    public enum Connectivity {

        /** The six cells sharing a face. What air connectivity means. */
        FACES,

        /** All twenty-six surrounding cells, diagonals included. */
        ALL
    }

    /** What a fill found, and whether it finished looking. */
    public static final class Region {

        private final Set<Vec3i> cells;
        private final boolean complete;

        private Region(Set<Vec3i> cells, boolean complete) {
            this.cells = cells;
            this.complete = complete;
        }

        /** @return the cells, in the order the walk reached them: nearest first. */
        public Set<Vec3i> getCells() {
            return Collections.unmodifiableSet(cells);
        }

        /**
         * @return whether the region was fully explored
         *
         * <p>{@code false} means the budget ran out with cells still queued, so the
         * region is at least this big and nothing can be concluded from its size.
         */
        public boolean isComplete() {
            return complete;
        }

        public int size() {
            return cells.size();
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }

        public boolean contains(Vec3i cell) {
            return cells.contains(cell);
        }

        @Override
        public String toString() {
            return "Region[" + cells.size() + (complete ? " cells]" : " cells, truncated]");
        }
    }

    /**
     * @param passable whether a cell belongs to the region and can be walked through
     * @param limit the most cells to visit before giving up
     * @return the region, empty if the start itself is not passable
     */
    public static Region from(Vec3i start, Predicate<Vec3i> passable, int limit) {
        return from(start, passable, limit, Connectivity.FACES);
    }

    public static Region from(Vec3i start, Predicate<Vec3i> passable, int limit, Connectivity connectivity) {
        Validate.notNull(start, "start");
        Validate.notNull(passable, "passable");
        Validate.check(limit > 0, "limit must be positive");

        Set<Vec3i> region = new LinkedHashSet<>();
        if (!passable.test(start)) {
            return new Region(region, true);
        }

        // Positions are packed into longs for the visited set: one primitive per
        // cell rather than an object, which matters at a few thousand cells.
        Set<Long> seen = new HashSet<>();
        Deque<Vec3i> queue = new ArrayDeque<>();
        seen.add(start.asLong());
        queue.add(start);

        while (!queue.isEmpty()) {
            if (region.size() >= limit) {
                return new Region(region, false);
            }
            Vec3i current = queue.poll();
            region.add(current);
            for (Vec3i neighbour : neighbours(current, connectivity)) {
                if (!seen.add(neighbour.asLong())) {
                    continue;
                }
                if (passable.test(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return new Region(region, true);
    }

    /**
     * @return whether the region containing {@code start} is closed and smaller
     *         than {@code limit}
     *
     * <p>The question a hole or enclosure check is really asking. Answering it
     * from {@link Region#size()} alone is the mistake: a fill that hit its budget
     * also returns a small number of cells.
     */
    public static boolean isEnclosed(Vec3i start, Predicate<Vec3i> passable, int limit) {
        return from(start, passable, limit).isComplete();
    }

    private static Vec3i[] neighbours(Vec3i cell, Connectivity connectivity) {
        if (connectivity == Connectivity.FACES) {
            Direction[] directions = Direction.values();
            Vec3i[] faces = new Vec3i[directions.length];
            for (int i = 0; i < directions.length; i++) {
                faces[i] = cell.offset(directions[i]);
            }
            return faces;
        }
        Vec3i[] all = new Vec3i[26];
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        all[index++] = cell.add(dx, dy, dz);
                    }
                }
            }
        }
        return all;
    }
}
