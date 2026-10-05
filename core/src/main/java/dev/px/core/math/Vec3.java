package dev.px.core.math;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * An immutable 3D double vector: world positions, motion, and directions.
 *
 * <p>Adapters convert to and from the game's own vector type at the boundary, so
 * everything above the adapter speaks one type across every version.
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class Vec3 {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    private final double x;
    private final double y;
    private final double z;

    public static Vec3 of(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 scale(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double distanceTo(Vec3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Distance ignoring the vertical axis. What range checks usually want. */
    public double horizontalDistanceTo(Vec3 other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Squared distance, for comparisons that do not need the square root. */
    public double squaredDistanceTo(Vec3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public Vec3 normalize() {
        double length = length();
        return length == 0d ? ZERO : new Vec3(x / length, y / length, z / length);
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vec3 cross(Vec3 other) {
        return new Vec3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x);
    }

    public Vec3 lerp(Vec3 target, double progress) {
        return new Vec3(
                x + (target.x - x) * progress,
                y + (target.y - y) * progress,
                z + (target.z - z) * progress);
    }

    /** @return the yaw and pitch that point from this position to {@code target}. */
    public Vec2 rotationTo(Vec3 target) {
        double dx = target.x - x;
        double dy = target.y - y;
        double dz = target.z - z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90d);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return Vec2.rotation(MathUtil.wrapDegrees(yaw), pitch);
    }

    public Vec3 withY(double newY) {
        return new Vec3(x, newY, z);
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f, %.2f)", x, y, z);
    }
}
