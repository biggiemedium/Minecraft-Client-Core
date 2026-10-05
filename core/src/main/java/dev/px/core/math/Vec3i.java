package dev.px.core.math;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * An immutable integer position on the block grid.
 *
 * <p>{@link Vec3} is where something is; this is which cell it is in. The
 * distinction matters as soon as anything reasons about the world as a grid
 * &mdash; a raycast walking cells, a pathfinder expanding neighbours, a flood
 * fill marking what it has seen. Done with doubles, all three have to round at
 * every step and each rounding is a chance to land one cell off.
 *
 * <p>The conversion happens once, at {@link #floorOf}, and it floors rather than
 * truncates: {@code (int) -0.5} is 0, which puts everything in the negative half
 * of the world one block too high. That single cast is the most common source of
 * off-by-one bugs in block code.
 *
 * <p>{@link #asLong()} packs a position into a primitive key, which is what makes
 * a visited set in a flood fill or a cell index in a spatial grid cheap: no
 * object allocated per lookup, and no hashing of three fields.
 *
 * <p><b>Complexity.</b> Every operation here is O(1) and allocation-free apart
 * from the returned instance.
 * object allocated per lookup, and no hashing of three fields.
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class Vec3i {

    public static final Vec3i ZERO = new Vec3i(0, 0, 0);

    /**
     * Bits given to each axis by {@link #asLong()}, matching the shape of a world:
     * horizontally huge, vertically small.
     */
    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 64 - X_BITS - Z_BITS;
    private static final long X_MASK = (1L << X_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;

    private final int x;
    private final int y;
    private final int z;

    public static Vec3i of(int x, int y, int z) {
        return new Vec3i(x, y, z);
    }

    /**
     * @return the cell containing {@code position}
     *
     * <p>Floors, so a position at -0.5 is in cell -1. Casting to {@code int}
     * instead would round it towards zero and answer 0.
     *
     * <pre>
     * floor(v) = (int) v - (v is negative and not whole ? 1 : 0)
     * </pre>
     * <p>Floors, so a position at -0.5 is in cell -1. Casting to {@code int}
     * instead would round it towards zero and answer 0.
     */
    public static Vec3i floorOf(double x, double y, double z) {
        return new Vec3i(floor(x), floor(y), floor(z));
    }

    public static Vec3i floorOf(Vec3 position) {
        return floorOf(position.getX(), position.getY(), position.getZ());
    }

    /** Java's {@code Math.floor} returns a double; this stays in integers. */
    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    // ---------------------------------------------------------- conversion

    /** @return the corner of the cell: the position a block's coordinates refer to. */
    public Vec3 toVec3() {
        return Vec3.of(x, y, z);
    }

    /** @return the middle of the cell. What to aim at, and what to measure from. */
    public Vec3 center() {
        return Vec3.of(x + 0.5d, y + 0.5d, z + 0.5d);
    }

    /** @return the unit cube this cell occupies. */
    public Box toBox() {
        return Box.block(x, y, z);
    }

    // ------------------------------------------------------------- movement

    public Vec3i add(int dx, int dy, int dz) {
        return dx == 0 && dy == 0 && dz == 0 ? this : new Vec3i(x + dx, y + dy, z + dz);
    }

    public Vec3i add(Vec3i other) {
        return add(other.x, other.y, other.z);
    }

    public Vec3i subtract(Vec3i other) {
        return add(-other.x, -other.y, -other.z);
    }

    /** @return the neighbouring cell one step along {@code direction}. */
    public Vec3i offset(Direction direction) {
        return add(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    /** @return the cell {@code distance} steps along {@code direction}. */
    public Vec3i offset(Direction direction, int distance) {
        return add(direction.getOffsetX() * distance,
                direction.getOffsetY() * distance,
                direction.getOffsetZ() * distance);
    }

    public Vec3i up() {
        return add(0, 1, 0);
    }

    public Vec3i up(int distance) {
        return add(0, distance, 0);
    }

    public Vec3i down() {
        return add(0, -1, 0);
    }

    public Vec3i down(int distance) {
        return add(0, -distance, 0);
    }

    public Vec3i withY(int newY) {
        return newY == y ? this : new Vec3i(x, newY, z);
    }

    // ------------------------------------------------------------- distance

    /** @return squared distance between cell centres. For comparisons, no square root. */
    public int squaredDistanceTo(Vec3i other) {
        int dx = x - other.x;
        int dy = y - other.y;
        int dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(Vec3i other) {
        return Math.sqrt(squaredDistanceTo(other));
    }

    /**
     * @return steps along the axes between the two cells
     *
     * <p>The honest distance when movement is grid-locked, and the heuristic a
     * pathfinder restricted to the six faces should use.
     */
    public int manhattanDistanceTo(Vec3i other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    /** @return whether the cells share a face. Diagonals are not adjacent. */
    public boolean isAdjacentTo(Vec3i other) {
        return manhattanDistanceTo(other) == 1;
    }

    // -------------------------------------------------------------- packing

    /**
     * @return the position as one primitive, suitable for a {@code HashMap} key
     *
     * <p>Valid for the range a world actually spans: 26 bits each of X and Z
     * (&plusmn;33.5 million) and 12 of Y (-2048 to 2047). Outside that the value
     * wraps rather than throwing, because this sits in the inner loop of every
     * grid algorithm here and a bounds check per lookup is not worth paying for a
     * coordinate no world produces.
     *
     * <p>The layout, most significant bit first:
     *
     * <pre>
     * key = (x and 0x3FFFFFF) shifted left 38
     *     or (y and 0xFFF)    shifted left 26
     *     or (z and 0x3FFFFFF)
     * </pre>
     * coordinate no world produces.
     */
    public long asLong() {
        return asLong(x, y, z);
    }

    public static long asLong(int x, int y, int z) {
        return ((long) x & X_MASK) << (Y_BITS + Z_BITS)
                | ((long) y & Y_MASK) << Z_BITS
                | ((long) z & Z_MASK);
    }

    /** @return the position a {@link #asLong()} key came from. */
    public static Vec3i fromLong(long packed) {
        int x = (int) (packed << (64 - X_BITS - Y_BITS - Z_BITS) >> (Y_BITS + Z_BITS));
        int y = (int) (packed << (64 - Y_BITS - Z_BITS) >> (64 - Y_BITS));
        int z = (int) (packed << (64 - Z_BITS) >> (64 - Z_BITS));
        return new Vec3i(x, y, z);
    }

    @Override
    public String toString() {
        return "[" + x + ", " + y + ", " + z + "]";
    }
}
