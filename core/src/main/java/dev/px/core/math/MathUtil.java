package dev.px.core.math;

import java.util.concurrent.ThreadLocalRandom;

/** Numeric helpers shared across modules, rendering, and animation. */
public final class MathUtil {

    private MathUtil() {
    }

    // ------------------------------------------------------------- clamping

    public static int clamp(int value, int min, int max) {
        return value < min ? min : value > max ? max : value;
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }

    /** Clamps to 0..1. The common case for animation progress and alpha. */
    public static float saturate(float value) {
        return clamp(value, 0f, 1f);
    }

    // ---------------------------------------------------------- interpolation

    public static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    public static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    /** @return where {@code value} sits between {@code from} and {@code to}, as 0..1. */
    public static float inverseLerp(float from, float to, float value) {
        return from == to ? 0f : saturate((value - from) / (to - from));
    }

    /** Smooth Hermite interpolation. Softer than {@link #lerp} at both ends. */
    public static float smoothStep(float from, float to, float progress) {
        float t = saturate(progress);
        return lerp(from, to, t * t * (3f - 2f * t));
    }

    /**
     * Frame-rate independent approach towards a target.
     *
     * <p>A plain {@code current += (target - current) * 0.1f} moves faster at
     * higher frame rates, which is why HUD animations feel different on different
     * machines. This compensates using the frame time.
     *
     * @param smoothing fraction of the remaining distance covered per second, 0..1
     * @param deltaSeconds time since the previous frame
     */
    public static float approach(float current, float target, float smoothing, float deltaSeconds) {
        float factor = 1f - (float) Math.pow(1f - saturate(smoothing), deltaSeconds * 60f);
        return current + (target - current) * factor;
    }

    // ------------------------------------------------------------- rotation

    /** Wraps an angle into -180..180, the range rotation maths expects. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        }
        if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360d;
        if (wrapped >= 180d) {
            wrapped -= 360d;
        }
        if (wrapped < -180d) {
            wrapped += 360d;
        }
        return wrapped;
    }

    /** @return the shortest signed angular difference from {@code from} to {@code to}. */
    public static float angleDifference(float from, float to) {
        return wrapDegrees(to - from);
    }

    /**
     * Steps an angle towards a target by at most {@code maxStep} degrees.
     * Takes the short way round, so it never spins the long way for one degree.
     */
    public static float stepAngle(float current, float target, float maxStep) {
        float difference = angleDifference(current, target);
        float step = clamp(difference, -maxStep, maxStep);
        return wrapDegrees(current + step);
    }

    // --------------------------------------------------------------- random

    public static double random(double min, double max) {
        return min == max ? min : ThreadLocalRandom.current().nextDouble(min, max);
    }

    public static float random(float min, float max) {
        return (float) random((double) min, max);
    }

    /** Inclusive on both ends, which is what a min/max CPS setting means. */
    public static int randomInt(int min, int max) {
        return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public static boolean chance(float probability) {
        return ThreadLocalRandom.current().nextFloat() < probability;
    }

    // --------------------------------------------------------------- rounding

    public static double round(double value, int decimals) {
        double factor = Math.pow(10d, decimals);
        return Math.round(value * factor) / factor;
    }

    public static float round(float value, int decimals) {
        return (float) round((double) value, decimals);
    }
}
