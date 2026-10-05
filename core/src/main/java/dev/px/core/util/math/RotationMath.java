package dev.px.core.util.math;

import dev.px.core.math.MathUtil;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;

/**
 * Rotation maths: look vectors, angular distance, and turning smoothly.
 *
 * <p>Sits on top of what {@link dev.px.core.math.Vec3#rotationTo} and
 * {@link MathUtil#stepAngle} already provide &mdash; pointing at something and
 * wrapping an angle are there, not here. This is the layer above: treating yaw
 * and pitch as one rotation rather than two independent floats, which is where
 * the mistakes live.
 *
 * <p>Two of them, specifically. Pitch does not wrap, it clamps: a rotation of 120
 * degrees down is not a rotation at all, and sending one is instantly visible.
 * And yaw does wrap, so the difference between 179 and -179 is two degrees, not
 * 358 &mdash; a turn that takes the arithmetic answer spins the player most of
 * the way round to look at something just to their left.
 *
 * <p><b>Complexity.</b> Every method here is O(1).
 *
 * <p>Rotations use {@link Vec2}, where x is yaw and y is pitch;
 * {@link Vec2#rotation} names them at construction.
 */
public final class RotationMath {

    /** Straight down and straight up. Pitch never leaves this range. */
    public static final float MIN_PITCH = -90f;
    public static final float MAX_PITCH = 90f;

    private RotationMath() {
    }

    // ---------------------------------------------------------- normalising

    /** @return {@code pitch} clamped to the range a head can actually reach. */
    public static float clampPitch(float pitch) {
        return MathUtil.clamp(pitch, MIN_PITCH, MAX_PITCH);
    }

    /**
     * @return the rotation with yaw wrapped to -180..180 and pitch clamped
     *
     * <p>Worth running over anything computed rather than read: an unwrapped yaw
     * accumulates over a session until the numbers stop matching the server's,
     * and an out-of-range pitch is a giveaway on its own.
     */
    public static Vec2 normalize(Vec2 rotation) {
        return Vec2.rotation(
                MathUtil.wrapDegrees(rotation.getYaw()),
                clampPitch(rotation.getPitch()));
    }

    // ------------------------------------------------------------ distance

    /**
     * @return the shortest angular distance between two rotations, in degrees
     *
     * <pre>
     * d = √(wrap(yaw₂ - yaw₁)² + (pitch₂ - pitch₁)²)
     * </pre>
     *
     * <p>Combines both axes, so it answers "how far is this from where I am
     * looking" in one number &mdash; the threshold a targeting module checks
     * before it decides it is aimed.
     */
    public static float difference(Vec2 from, Vec2 to) {
        float yaw = MathUtil.angleDifference(from.getYaw(), to.getYaw());
        float pitch = to.getPitch() - from.getPitch();
        return (float) Math.sqrt(yaw * yaw + pitch * pitch);
    }

    /**
     * @return the angle between where {@code rotation} looks and where
     *         {@code target} lies: {@code acos(look · normalise(target - eye))}
     */
    public static float angleTo(Vec2 rotation, Vec3 eye, Vec3 target) {
        Vec3 look = direction(rotation);
        Vec3 toTarget = target.subtract(eye).normalize();
        double dot = MathUtil.clamp(look.dot(toTarget), -1d, 1d);
        return (float) Math.toDegrees(Math.acos(dot));
    }

    /** @return whether {@code target} falls inside a cone of {@code fovDegrees} around the crosshair. */
    public static boolean isWithinFov(Vec2 rotation, Vec3 eye, Vec3 target, float fovDegrees) {
        return angleTo(rotation, eye, target) <= fovDegrees / 2f;
    }

    // ------------------------------------------------------------ vectors

    /**
     * @return the unit vector a rotation points along
     *
     * <pre>
     * x = -sin(yaw)·cos(pitch)
     * y = -sin(pitch)
     * z =  cos(yaw)·cos(pitch)
     * </pre>
     */
    public static Vec3 direction(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        return Vec3.of(
                -Math.sin(yawRadians) * horizontal,
                -Math.sin(pitchRadians),
                Math.cos(yawRadians) * horizontal);
    }

    public static Vec3 direction(Vec2 rotation) {
        return direction(rotation.getYaw(), rotation.getPitch());
    }

    /**
     * @return the point {@code distance} blocks along the rotation from {@code origin}
     *
     * <p>Where a raytrace would start and end, without needing a world to trace
     * against: reach checks, placement previews, and the end of a debug line.
     */
    public static Vec3 project(Vec3 origin, Vec2 rotation, double distance) {
        return origin.add(direction(rotation).scale(distance));
    }

    // -------------------------------------------------------------- turning

    /**
     * Turns from {@code current} towards {@code target} by at most one step on
     * each axis, taking the short way round on yaw.
     *
     * <p>Called once a tick with the same target, this traces a turn out over
     * several ticks instead of teleporting the head, which is both what a human
     * does and what a server expects to see.
     */
    public static Vec2 step(Vec2 current, Vec2 target, float maxYawStep, float maxPitchStep) {
        float yaw = MathUtil.stepAngle(current.getYaw(), target.getYaw(), maxYawStep);
        float pitchDifference = target.getPitch() - current.getPitch();
        float pitch = current.getPitch() + MathUtil.clamp(pitchDifference, -maxPitchStep, maxPitchStep);
        return Vec2.rotation(yaw, clampPitch(pitch));
    }

    /** Steps both axes by the same limit. The common case. */
    public static Vec2 step(Vec2 current, Vec2 target, float maxStep) {
        return step(current, target, maxStep, maxStep);
    }

    /**
     * @return {@code target} rounded to a rotation the player's mouse could have
     *         produced at that sensitivity
     *
     * <p>A mouse cannot turn by an arbitrary amount. The game converts pixel
     * movement through a cubic curve, so every reachable rotation is a whole
     * multiple of one step away from the last one &mdash; and a rotation that is
     * not a multiple could only have come from code. Snapping to the grid costs a
     * fraction of a degree of accuracy and removes the tell.
     *
     * <pre>
     * g    = (0.6·sensitivity + 0.2)³ · 1.2
     * yaw' = previous + round(wrap(target - previous) / g) · g
     * </pre>
     *
     * @param sensitivity the in-game mouse sensitivity slider, 0..1
     */
    public static Vec2 snapToSensitivity(Vec2 previous, Vec2 target, float sensitivity) {
        float step = sensitivityStep(sensitivity);
        if (step <= 0f) {
            return normalize(target);
        }
        float yaw = previous.getYaw()
                + Math.round(MathUtil.angleDifference(previous.getYaw(), target.getYaw()) / step) * step;
        float pitch = previous.getPitch()
                + Math.round((target.getPitch() - previous.getPitch()) / step) * step;
        return Vec2.rotation(MathUtil.wrapDegrees(yaw), clampPitch(pitch));
    }

    /**
     * @return the smallest rotation a single mouse count produces, in degrees:
     *         {@code (0.6·sensitivity + 0.2)³ · 1.2}
     */
    public static float sensitivityStep(float sensitivity) {
        float scaled = sensitivity * 0.6f + 0.2f;
        return scaled * scaled * scaled * 1.2f;
    }
}
