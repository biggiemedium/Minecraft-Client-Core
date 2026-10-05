package dev.px.core.render.animation;

import lombok.Getter;

/**
 * A value that eases towards a target over a fixed duration.
 *
 * <p>Time-based, not frame-based. The old animations advanced by a fixed step
 * each call, so the same fade took twice as long at 30 FPS as at 60 and had to
 * be retuned per machine. This reads the clock, so the duration is the duration.
 *
 * <p>There is no update method to remember to call: {@link #get()} computes the
 * current value on demand, which also means an animation nothing is drawing
 * costs nothing.
 *
 * <pre>{@code
 * private final Animation hover = Animation.of(0f, 150, Easing.QUAD_OUT);
 *
 * hover.target(isHovered ? 1f : 0f);
 * Render.rect(x, y, w, h, base.lerp(highlight, hover.get()));
 * }</pre>
 */
@Getter
public final class Animation {

    private float origin;
    private float target;
    private long startedAt;
    private long durationMillis;
    private Easing easing;

    private Animation(float initial, long durationMillis, Easing easing) {
        this.origin = initial;
        this.target = initial;
        this.durationMillis = durationMillis;
        this.easing = easing;
        this.startedAt = System.currentTimeMillis() - durationMillis;
    }

    public static Animation of(float initial, long durationMillis, Easing easing) {
        return new Animation(initial, durationMillis, easing);
    }

    /** A 0-to-1 fade, the shape most UI animations take. */
    public static Animation fade(long durationMillis, Easing easing) {
        return new Animation(0f, durationMillis, easing);
    }

    /**
     * Sets where the value is heading.
     *
     * <p>Re-targeting mid-flight restarts from wherever the value currently is,
     * so a toggle flipped twice quickly moves smoothly rather than jumping.
     * Setting the target it already has does nothing, so this is safe to call
     * every frame, which is the intended usage.
     */
    public Animation target(float newTarget) {
        if (newTarget == target) {
            return this;
        }
        this.origin = get();
        this.target = newTarget;
        this.startedAt = System.currentTimeMillis();
        return this;
    }

    /** Targets 1 when true and 0 when false. Pairs with a boolean setting or hover state. */
    public Animation target(boolean on) {
        return target(on ? 1f : 0f);
    }

    /** @return the current eased value. */
    public float get() {
        if (durationMillis <= 0L) {
            return target;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed >= durationMillis) {
            return target;
        }
        return easing.between(origin, target, elapsed / (float) durationMillis);
    }

    /** @return the value scaled into a range. Saves a lerp at the call site. */
    public float get(float from, float to) {
        return from + (to - from) * get();
    }

    public boolean isFinished() {
        return System.currentTimeMillis() - startedAt >= durationMillis;
    }

    /** @return whether the value is at or heading to zero. Used to skip drawing entirely. */
    public boolean isIdleAtZero() {
        return target == 0f && isFinished();
    }

    /** Jumps straight to a value, cancelling any movement. For state restored from a config. */
    public Animation snapTo(float value) {
        this.origin = value;
        this.target = value;
        this.startedAt = System.currentTimeMillis() - durationMillis;
        return this;
    }

    public Animation duration(long millis) {
        this.durationMillis = millis;
        return this;
    }

    public Animation easing(Easing curve) {
        this.easing = curve;
        return this;
    }
}
