package dev.px.core.math;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * An immutable axis-aligned bounding box in world space.
 *
 * <p>What {@code Render.box(...)} draws and what an adapter converts a game
 * bounding box into. Bounds are normalised at construction, so min is always
 * below max whichever way round the corners were given.
 */
@Getter
@EqualsAndHashCode
public final class Box {

    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;

    private Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static Box of(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Box(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public static Box of(Vec3 corner, Vec3 opposite) {
        return of(corner.getX(), corner.getY(), corner.getZ(),
                opposite.getX(), opposite.getY(), opposite.getZ());
    }

    /** A box centred horizontally on {@code base}, rising from it. The entity hitbox shape. */
    public static Box around(Vec3 base, double width, double height) {
        double half = width / 2d;
        return of(base.getX() - half, base.getY(), base.getZ() - half,
                base.getX() + half, base.getY() + height, base.getZ() + half);
    }

    /** A unit cube at integer coordinates. The block shape. */
    public static Box block(double x, double y, double z) {
        return of(x, y, z, x + 1, y + 1, z + 1);
    }

    public Vec3 getMin() {
        return Vec3.of(minX, minY, minZ);
    }

    public Vec3 getMax() {
        return Vec3.of(maxX, maxY, maxZ);
    }

    public Vec3 getCenter() {
        return Vec3.of((minX + maxX) / 2d, (minY + maxY) / 2d, (minZ + maxZ) / 2d);
    }

    public double getWidth() {
        return maxX - minX;
    }

    public double getHeight() {
        return maxY - minY;
    }

    public double getDepth() {
        return maxZ - minZ;
    }

    /** Grows the box by {@code amount} on every axis. Negative shrinks it. */
    public Box expand(double amount) {
        return new Box(minX - amount, minY - amount, minZ - amount,
                maxX + amount, maxY + amount, maxZ + amount);
    }

    /**
     * Grows the box along a motion vector: forwards on each positive component,
     * backwards on each negative one.
     *
     * <p>The region a moving box could touch this tick, which is what a collision
     * sweep collects obstacles from before it starts clipping.
     */
    public Box stretch(double dx, double dy, double dz) {
        return new Box(
                minX + Math.min(dx, 0d), minY + Math.min(dy, 0d), minZ + Math.min(dz, 0d),
                maxX + Math.max(dx, 0d), maxY + Math.max(dy, 0d), maxZ + Math.max(dz, 0d));
    }

    public Box offset(double dx, double dy, double dz) {
        return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public Box offset(Vec3 delta) {
        return offset(delta.getX(), delta.getY(), delta.getZ());
    }

    public boolean contains(Vec3 point) {
        return point.getX() >= minX && point.getX() <= maxX
                && point.getY() >= minY && point.getY() <= maxY
                && point.getZ() >= minZ && point.getZ() <= maxZ;
    }

    public boolean intersects(Box other) {
        return maxX > other.minX && minX < other.maxX
                && maxY > other.minY && minY < other.maxY
                && maxZ > other.minZ && minZ < other.maxZ;
    }

    /** @return the point of the box nearest {@code point}; the point itself when it is inside */
    public Vec3 closestPoint(Vec3 point) {
        return Vec3.of(
                MathUtil.clamp(point.getX(), minX, maxX),
                MathUtil.clamp(point.getY(), minY, maxY),
                MathUtil.clamp(point.getZ(), minZ, maxZ));
    }

    /** @return the squared distance from {@code point} to the nearest point of the box; 0 inside it */
    public double squaredDistanceTo(Vec3 point) {
        double dx = Math.max(Math.max(minX - point.getX(), 0d), point.getX() - maxX);
        double dy = Math.max(Math.max(minY - point.getY(), 0d), point.getY() - maxY);
        double dz = Math.max(Math.max(minZ - point.getZ(), 0d), point.getZ() - maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /** @return the distance from {@code point} to the nearest point of the box; 0 inside it */
    public double distanceTo(Vec3 point) {
        return Math.sqrt(squaredDistanceTo(point));
    }

    /**
     * Shrinks the box by {@code amount} on every side.
     *
     * <p>Unlike {@code expand(-amount)}, an axis too short to shrink that far
     * collapses to its midpoint rather than turning inside out, so the result is
     * always a box inside this one.
     */
    public Box inset(double amount) {
        double centreX = (minX + maxX) / 2d;
        double centreY = (minY + maxY) / 2d;
        double centreZ = (minZ + maxZ) / 2d;
        return new Box(
                Math.min(minX + amount, centreX), Math.min(minY + amount, centreY), Math.min(minZ + amount, centreZ),
                Math.max(maxX - amount, centreX), Math.max(maxY - amount, centreY), Math.max(maxZ - amount, centreZ));
    }

    /** @return the eight corners, in the order a wireframe renderer wants them. */
    public Vec3[] corners() {
        return new Vec3[] {
                Vec3.of(minX, minY, minZ), Vec3.of(maxX, minY, minZ),
                Vec3.of(maxX, minY, maxZ), Vec3.of(minX, minY, maxZ),
                Vec3.of(minX, maxY, minZ), Vec3.of(maxX, maxY, minZ),
                Vec3.of(maxX, maxY, maxZ), Vec3.of(minX, maxY, maxZ)
        };
    }

    @Override
    public String toString() {
        return String.format("Box[%.2f,%.2f,%.2f -> %.2f,%.2f,%.2f]", minX, minY, minZ, maxX, maxY, maxZ);
    }
}
