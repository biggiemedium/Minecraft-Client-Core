package dev.px.core.math;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * An immutable 2D double vector.
 *
 * <p>Used for screen coordinates and for yaw/pitch pairs. Core defines its own
 * rather than borrowing the game's so nothing in the maths layer needs a
 * version-specific type.
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class Vec2 {

    public static final Vec2 ZERO = new Vec2(0, 0);

    private final double x;
    private final double y;

    public static Vec2 of(double x, double y) {
        return new Vec2(x, y);
    }

    /** Names the components for the rotation case, where x is yaw and y is pitch. */
    public static Vec2 rotation(float yaw, float pitch) {
        return new Vec2(yaw, pitch);
    }

    public float getYaw() {
        return (float) x;
    }

    public float getPitch() {
        return (float) y;
    }

    public Vec2 add(Vec2 other) {
        return new Vec2(x + other.x, y + other.y);
    }

    public Vec2 add(double dx, double dy) {
        return new Vec2(x + dx, y + dy);
    }

    public Vec2 subtract(Vec2 other) {
        return new Vec2(x - other.x, y - other.y);
    }

    public Vec2 scale(double factor) {
        return new Vec2(x * factor, y * factor);
    }

    public double length() {
        return Math.sqrt(x * x + y * y);
    }

    public double distanceTo(Vec2 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public Vec2 normalize() {
        double length = length();
        return length == 0d ? ZERO : new Vec2(x / length, y / length);
    }

    public Vec2 lerp(Vec2 target, double progress) {
        return new Vec2(x + (target.x - x) * progress, y + (target.y - y) * progress);
    }

    public float getFloatX() {
        return (float) x;
    }

    public float getFloatY() {
        return (float) y;
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)", x, y);
    }
}
