package dev.px.core.util.render;

import dev.px.core.math.MathUtil;
import dev.px.core.render.Color;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An immutable multi-stop colour ramp, sampled by position.
 *
 * <p>{@link Color#lerp} blends two colours, and two is enough for a button. It is
 * not enough for the things a client actually draws: a five-colour theme sweep
 * across a panel, a health bar that passes through amber, a spectrum behind a
 * graph. Written by hand, three stops means picking which pair a given progress
 * falls between, and that arithmetic gets copied and mis-copied.
 *
 * <p>Stops are evenly spaced. Uneven spacing is a real thing to want and a
 * larger API; when a stop needs more of the ramp, repeat it.
 *
 * <p><b>Complexity.</b> {@link #at} and {@link #atWrapping} are O(1) &mdash; the
 * stop is indexed, not searched:
 *
 * <pre>
 * at(t):         u = clamp(t,0,1)·(n-1),  lerp(stop[⌊u⌋], stop[⌊u⌋+1], u - ⌊u⌋)
 * atWrapping(t): u = frac(t)·n,           lerp(stop[i], stop[(i+1) mod n], frac(u))
 * </pre>
 *
 * <p>{@link #sample}, {@link #reversed()} and {@link #withAlpha} build a new
 * object and are O(n).
 *
 * <pre>{@code
 * private static final Gradient BAR = Gradient.of(Color.RED, Color.ORANGE, Color.GREEN);
 *
 * Render.rect(x, y, w, h, BAR.at(health / maxHealth));
 * Render.rect(x, y, w, h, Gradient.RAINBOW.cycling(4000));
 * }</pre>
 */
public final class Gradient {

    /** The full spectrum, wrapping cleanly so {@link #cycling} has no seam. */
    public static final Gradient RAINBOW = Gradient.of(
            Color.rgb(0xFF0000), Color.rgb(0xFFFF00), Color.rgb(0x00FF00),
            Color.rgb(0x00FFFF), Color.rgb(0x0000FF), Color.rgb(0xFF00FF));

    private final Color[] stops;

    private Gradient(Color[] stops) {
        this.stops = stops;
    }

    /** @throws IllegalArgumentException if fewer than two stops are given */
    public static Gradient of(Color... stops) {
        Validate.notNull(stops, "stops");
        Validate.check(stops.length >= 2, "a gradient needs at least two stops");
        return new Gradient(Arrays.copyOf(stops, stops.length));
    }

    public static Gradient of(List<Color> stops) {
        return of(stops.toArray(new Color[0]));
    }

    /**
     * @param progress position along the ramp, clamped to 0..1
     * @return the colour at that position
     */
    public Color at(float progress) {
        float clamped = MathUtil.saturate(progress);
        if (clamped >= 1f) {
            return stops[stops.length - 1];
        }
        float scaled = clamped * (stops.length - 1);
        int index = (int) scaled;
        return stops[index].lerp(stops[index + 1], scaled - index);
    }

    /**
     * @return the colour at {@code progress}, wrapping past the last stop back to
     *         the first
     *
     * <p>The difference matters for anything that loops: sampled with {@link #at}
     * a repeating animation snaps from the last colour to the first, and the jump
     * is the only thing anyone notices.
     */
    public Color atWrapping(float progress) {
        float wrapped = progress - (float) Math.floor(progress);
        float scaled = wrapped * stops.length;
        int index = (int) scaled % stops.length;
        return stops[index].lerp(stops[(index + 1) % stops.length], scaled - (int) scaled);
    }

    /**
     * @return the colour for the current moment of a loop lasting {@code periodMillis}
     *
     * <p>Stateless, like {@link ColorUtil#rainbow(long)}: call it from a draw
     * method and the ramp animates with nothing to store or update.
     */
    public Color cycling(long periodMillis) {
        return cycling(periodMillis, 0f);
    }

    /** @param offset a fraction of the loop to shift by, for a travelling wave */
    public Color cycling(long periodMillis, float offset) {
        if (periodMillis <= 0L) {
            return at(offset);
        }
        return atWrapping((System.currentTimeMillis() % periodMillis) / (float) periodMillis + offset);
    }

    /** @return {@code count} colours sampled evenly along the ramp, ends included. */
    public Color[] sample(int count) {
        Validate.check(count > 0, "count must be positive");
        Color[] sampled = new Color[count];
        for (int i = 0; i < count; i++) {
            sampled[i] = count == 1 ? at(0f) : at(i / (float) (count - 1));
        }
        return sampled;
    }

    public Gradient reversed() {
        Color[] flipped = new Color[stops.length];
        for (int i = 0; i < stops.length; i++) {
            flipped[i] = stops[stops.length - 1 - i];
        }
        return new Gradient(flipped);
    }

    /** @return every stop faded to {@code alpha}, for drawing the ramp translucent. */
    public Gradient withAlpha(float alpha) {
        Color[] faded = new Color[stops.length];
        for (int i = 0; i < stops.length; i++) {
            faded[i] = stops[i].withAlpha(alpha);
        }
        return new Gradient(faded);
    }

    public Color getStart() {
        return stops[0];
    }

    public Color getEnd() {
        return stops[stops.length - 1];
    }

    public int stopCount() {
        return stops.length;
    }

    public List<Color> getStops() {
        return Collections.unmodifiableList(Arrays.asList(stops));
    }

    @Override
    public String toString() {
        return "Gradient" + Arrays.toString(stops);
    }
}
