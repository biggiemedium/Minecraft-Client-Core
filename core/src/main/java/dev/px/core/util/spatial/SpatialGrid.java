package dev.px.core.util.spatial;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A uniform hash grid over 3D space, for "what is near here" without asking
 * everything.
 *
 * <p>The query every client module makes. Targeting wants the nearest player,
 * ESP wants what is in render distance, a crystal module wants entities beside a
 * position. Each one written the obvious way walks the full entity list, and they
 * do it independently: six modules times three hundred entities, sixty times a
 * second, mostly to reject things a hundred blocks away.
 *
 * <p>Bucketing by cell turns a range query into a visit of the cells the radius
 * actually covers. The list is walked once to build the grid and each query then
 * touches a handful of buckets, so the cost stops scaling with how much exists
 * and starts scaling with how much is close.
 *
 * <p>Choosing the cell size is the whole trick: around the radius usually queried
 * is right. Much smaller and a query walks hundreds of empty buckets; much larger
 * and every bucket holds everything, which is the linear scan again with extra
 * steps.
 *
 * <p><b>Complexity.</b> {@link #insert} is O(1) expected. A query visits the
 * buckets the radius spans and distance-checks what is in them, so with k
 * candidates found and m returned:
 *
 * <pre>
 * buckets touched = (⌊2r/s⌋ + 1)³        r = radius, s = cell size
 * forEachWithin   = O(buckets + k)
 * within          = O(buckets + k + m log m)   the sort
 * nearest         = O(buckets + k)
 * </pre>
 *
 * <p>That cubed term is the reason to size the cell near the radius: at s = r it
 * is 27 buckets, at s = r/4 it is over seven hundred.
 *
 * <p>Built to be thrown away. A grid is cheaper to {@link #clear()} and refill
 * each tick than to keep in step with things that move every tick, and a stale
 * spatial index is worse than none.
 *
 * <pre>{@code
 * grid.clear();
 * for (Entity entity : world.entities()) {
 *     grid.insert(entity.position(), entity);
 * }
 * List<Entity> nearby = grid.within(player.position(), 6d);
 * }</pre>
 *
 * <p>Not thread-safe.
 *
 * @param <T> what is being indexed
 */
public final class SpatialGrid<T> {

    private final Map<Long, List<Entry<T>>> cells = new HashMap<>();
    private final double cellSize;

    private int size;

    private SpatialGrid(double cellSize) {
        Validate.check(cellSize > 0d, "cellSize must be positive");
        this.cellSize = cellSize;
    }

    /** @param cellSize the edge length of a bucket. Pick roughly the radius you query */
    public static <T> SpatialGrid<T> of(double cellSize) {
        return new SpatialGrid<>(cellSize);
    }

    // ------------------------------------------------------------- building

    public void insert(double x, double y, double z, T value) {
        cells.computeIfAbsent(key(cell(x), cell(y), cell(z)), key -> new ArrayList<>())
                .add(new Entry<>(x, y, z, value));
        size++;
    }

    public void insert(Vec3 position, T value) {
        insert(position.getX(), position.getY(), position.getZ(), value);
    }

    public void clear() {
        cells.clear();
        size = 0;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** @return how many buckets currently hold anything. Useful for sizing the cell. */
    public int cellCount() {
        return cells.size();
    }

    public double getCellSize() {
        return cellSize;
    }

    // -------------------------------------------------------------- queries

    /** @return everything within {@code radius} of the point, nearest first. */
    public List<T> within(Vec3 center, double radius) {
        return within(center.getX(), center.getY(), center.getZ(), radius);
    }

    public List<T> within(double x, double y, double z, double radius) {
        List<Entry<T>> found = new ArrayList<>();
        forEachEntry(x, y, z, radius, found::add);
        // Sorted by distance because the caller almost always wants the closest,
        // and sorting a handful of candidates is cheaper than the scan it replaces.
        found.sort((left, right) -> Double.compare(
                left.squaredDistanceTo(x, y, z), right.squaredDistanceTo(x, y, z)));
        List<T> values = new ArrayList<>(found.size());
        for (Entry<T> entry : found) {
            values.add(entry.value);
        }
        return values;
    }

    /**
     * Hands every match to {@code action}, unsorted.
     *
     * <p>O(buckets + k), against O(buckets + k + m log m) for {@link #within}.
     *
     * <p>Allocates no list and does no sorting, which is what a per-frame query
     * that only needs to draw each match should use.
     */
    public void forEachWithin(Vec3 center, double radius, Consumer<T> action) {
        forEachWithin(center.getX(), center.getY(), center.getZ(), radius, action);
    }

    /** {@link #forEachWithin(Vec3, double, Consumer)} without building a {@code Vec3} for the centre. */
    public void forEachWithin(double x, double y, double z, double radius, Consumer<T> action) {
        forEachEntry(x, y, z, radius, entry -> action.accept(entry.value));
    }

    /** @return the closest thing within {@code radius}, or {@code null}. */
    public T nearest(Vec3 center, double radius) {
        return nearest(center.getX(), center.getY(), center.getZ(), radius);
    }

    public T nearest(double x, double y, double z, double radius) {
        Closest<T> closest = new Closest<>();
        forEachEntry(x, y, z, radius, entry -> {
            double distance = entry.squaredDistanceTo(x, y, z);
            if (distance < closest.distance) {
                closest.distance = distance;
                closest.value = entry.value;
            }
        });
        return closest.value;
    }

    /** @return whether anything at all lies within {@code radius}. */
    public boolean anyWithin(Vec3 center, double radius) {
        return nearest(center, radius) != null;
    }

    /** @return every indexed value, in no particular order. */
    public Collection<T> all() {
        List<T> values = new ArrayList<>(size);
        for (List<Entry<T>> bucket : cells.values()) {
            for (Entry<T> entry : bucket) {
                values.add(entry.value);
            }
        }
        return values;
    }

    // ------------------------------------------------------------ internals

    /**
     * Visits the buckets the sphere touches, rejecting the corners.
     *
     * <p>The cell walk is a box, so the bucket at the corner of the box is outside
     * the sphere; entries are still distance-checked individually. Skipping that
     * check would return things up to 73% further away than asked for.
     */
    private void forEachEntry(double x, double y, double z, double radius, Consumer<Entry<T>> action) {
        if (radius <= 0d || cells.isEmpty()) {
            return;
        }
        double squaredRadius = radius * radius;
        int minX = cell(x - radius);
        int minY = cell(y - radius);
        int minZ = cell(z - radius);
        int maxX = cell(x + radius);
        int maxY = cell(y + radius);
        int maxZ = cell(z + radius);
        for (int cellX = minX; cellX <= maxX; cellX++) {
            for (int cellY = minY; cellY <= maxY; cellY++) {
                for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                    List<Entry<T>> bucket = cells.get(key(cellX, cellY, cellZ));
                    if (bucket == null) {
                        continue;
                    }
                    for (Entry<T> entry : bucket) {
                        if (entry.squaredDistanceTo(x, y, z) <= squaredRadius) {
                            action.accept(entry);
                        }
                    }
                }
            }
        }
    }

    private int cell(double coordinate) {
        return (int) Math.floor(coordinate / cellSize);
    }

    /**
     * Cell coordinates mixed into one key.
     *
     * <p>A collision would put two cells in one bucket, which costs a few extra
     * distance checks and changes no answer: every candidate is distance-checked
     * anyway. That is why a 64-bit mix is enough here without a tie-break.
     *
     * <p>Not {@link dev.px.core.math.Vec3i#asLong()}: cell coordinates are
     * unbounded in Y once the cell size is small, so this hashes rather than packs.
     */
    private static long key(int x, int y, int z) {
        long hash = x * 0x9E3779B97F4A7C15L;
        hash = (hash ^ y) * 0xC2B2AE3D27D4EB4FL;
        hash = (hash ^ z) * 0x165667B19E3779F9L;
        return hash ^ (hash >>> 31);
    }

    /** A value and where it was inserted. Positions are kept for the distance check. */
    private static final class Entry<T> {

        private final double x;
        private final double y;
        private final double z;
        private final T value;

        private Entry(double x, double y, double z, T value) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
        }

        private double squaredDistanceTo(double otherX, double otherY, double otherZ) {
            double dx = x - otherX;
            double dy = y - otherY;
            double dz = z - otherZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** A mutable pair for the nearest search, so the lambda has somewhere to write. */
    private static final class Closest<T> {

        private double distance = Double.MAX_VALUE;
        private T value;
    }
}
