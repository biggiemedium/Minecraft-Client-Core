package dev.px.core.math;

import lombok.Getter;

/**
 * The six axis-aligned directions on the block grid.
 *
 * <p>Needed because the grid algorithms produce them: a raycast reports the face
 * it entered a block through, a pathfinder steps one way or another, a block
 * placement needs a side to place against. Returning an {@code int} for that and
 * decoding it at the call site is how the meaning of "2" gets forgotten.
 *
 * <p>The axis convention is the game's &mdash; north is -Z, yaw 0 faces south
 * &mdash; which Core can encode without importing anything, exactly as
 * {@link Vec3#rotationTo} already encodes the yaw convention. An adapter maps its
 * own facing type onto this at the boundary and nothing above the adapter has to
 * know which version's enum it came from.
 *
 * <p><b>Complexity.</b> Every method here is O(1); the two that search do so over
 * a fixed six or four entries.
 * own facing type onto this at the boundary and nothing above the adapter has to
 * know which version's enum it came from.
 */
@Getter
public enum Direction {

    DOWN(0, -1, 0, Axis.Y),
    UP(0, 1, 0, Axis.Y),
    NORTH(0, 0, -1, Axis.Z),
    SOUTH(0, 0, 1, Axis.Z),
    WEST(-1, 0, 0, Axis.X),
    EAST(1, 0, 0, Axis.X);

    /** The three axes, so code can ask which one a direction runs along. */
    public enum Axis {
        X, Y, Z
    }

    /**
     * The four compass directions, in yaw order starting at south.
     *
     * <p>Indexed by {@code round(yaw / 90) & 3}, which is what makes
     * {@link #fromYaw} a lookup rather than a chain of comparisons.
     */
    private static final Direction[] HORIZONTAL = { SOUTH, WEST, NORTH, EAST };

    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final Axis axis;

    Direction(int offsetX, int offsetY, int offsetZ, Axis axis) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.axis = axis;
    }

    public Direction opposite() {
        switch (this) {
            case DOWN: return UP;
            case UP: return DOWN;
            case NORTH: return SOUTH;
            case SOUTH: return NORTH;
            case WEST: return EAST;
            default: return WEST;
        }
    }

    /** @return whether this is one of the four compass directions. */
    public boolean isHorizontal() {
        return axis != Axis.Y;
    }

    /** @return the direction as a unit vector. */
    public Vec3 toVector() {
        return Vec3.of(offsetX, offsetY, offsetZ);
    }

    /** @return the direction as a one-step grid offset. */
    public Vec3i toVec3i() {
        return Vec3i.of(offsetX, offsetY, offsetZ);
    }

    /**
     * @return the yaw of a player facing this way, or 0 for the vertical pair
     *
     * <p>South is 0 and west is 90, matching the game: yaw increases clockwise
     * from +Z.
     */
    public float toYaw() {
        switch (this) {
            case SOUTH: return 0f;
            case WEST: return 90f;
            case NORTH: return 180f;
            case EAST: return -90f;
            default: return 0f;
        }
    }

    // --------------------------------------------------------------- lookup

    /** @return the four compass directions, in yaw order from south. */
    public static Direction[] horizontals() {
        return HORIZONTAL.clone();
    }

    /**
     * @return the compass direction a player at this yaw is facing.
     *
     * <p>A lookup rather than a comparison chain: {@code index = round(wrap(yaw)
     * / 90) and 3}, indexing {@link #HORIZONTAL} in its yaw order.
     */
    public static Direction fromYaw(float yaw) {
        return HORIZONTAL[Math.round(MathUtil.wrapDegrees(yaw) / 90f) & 3];
    }

    /**
     * @return the direction most closely matching {@code vector}, or {@link #UP}
     *         for a zero vector
     *
     * <p>Whichever component is largest wins, which is the right answer for
     * snapping a look direction to a block face: {@code argmax(|x|, |y|, |z|)},
     * signed by that component.
     */
    public static Direction nearest(Vec3 vector) {
        double x = Math.abs(vector.getX());
        double y = Math.abs(vector.getY());
        double z = Math.abs(vector.getZ());
        if (x == 0d && y == 0d && z == 0d) {
            return UP;
        }
        if (x > y && x > z) {
            return vector.getX() > 0d ? EAST : WEST;
        }
        if (z >= x && z > y) {
            return vector.getZ() > 0d ? SOUTH : NORTH;
        }
        return vector.getY() > 0d ? UP : DOWN;
    }

    /** @return the direction from {@code from} to {@code to}, or {@code null} if they are not adjacent. */
    public static Direction between(Vec3i from, Vec3i to) {
        Vec3i delta = to.subtract(from);
        for (Direction direction : values()) {
            if (delta.getX() == direction.offsetX
                    && delta.getY() == direction.offsetY
                    && delta.getZ() == direction.offsetZ) {
                return direction;
            }
        }
        return null;
    }
}
