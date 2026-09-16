package dev.px.core.util.render;

import dev.px.core.math.MathUtil;
import dev.px.core.render.Color;

/**
 * Colour effects and conversions that {@link Color} itself does not carry.
 *
 * <p>The split is deliberate. {@link Color} is a value: a packed integer with
 * blending and component access, and nothing that reads a clock. Everything here
 * either animates, derives a colour from data, or converts for a backend, none
 * of which belongs on an immutable value type.
 *
 * <p><b>Complexity.</b> Every conversion and effect here is O(1);
 * {@link #average} is O(n) in the colours given.
 *
 * <p>The animated helpers read {@link System#currentTimeMillis()} rather than
 * taking a progress argument, so a HUD element gets a moving colour without
 * owning any state &mdash; calling {@link #rainbow(long)} from a draw method is
 * the whole implementation.
 */
public final class ColorUtil {

    private ColorUtil() {
    }

    // ------------------------------------------------------------ animated

    /** @return a fully saturated colour cycling through the spectrum once per {@code periodMillis}. */
    public static Color rainbow(long periodMillis) {
        return rainbow(periodMillis, 0f, 1f, 1f);
    }

    /**
     * @param offset a fraction of the cycle to shift by, 0..1
     * @return the rainbow colour at that offset
     *
     * <p>The offset is what makes a rainbow read as a gradient rather than a
     * flashing block: draw each character or each list row with an offset of
     * {@code index * 0.05f} and the wave travels along it.
     */
    public static Color rainbow(long periodMillis, float offset, float saturation, float brightness) {
        if (periodMillis <= 0L) {
            return Color.hsb(offset, saturation, brightness);
        }
        float progress = (System.currentTimeMillis() % periodMillis) / (float) periodMillis;
        return Color.hsb(progress + offset, saturation, brightness);
    }

    /** @return a rainbow shifted by one step per index. The per-character form. */
    public static Color rainbow(long periodMillis, int index, float spread) {
        return rainbow(periodMillis, index * spread, 1f, 1f);
    }

    /**
     * @return a colour easing back and forth between two, once per {@code periodMillis}
     *
     * <pre>
     * t = (1 - cos(2π · phase)) / 2
     * </pre>
     *
     * <p>Sine-shaped rather than linear, so it lingers at each end instead of
     * snapping round at the extremes. Reads as breathing; a linear equivalent
     * reads as a fault.
     */
    public static Color pulse(Color from, Color to, long periodMillis) {
        if (periodMillis <= 0L) {
            return from;
        }
        double phase = (System.currentTimeMillis() % periodMillis) / (double) periodMillis;
        float progress = (float) ((1d - Math.cos(phase * 2d * Math.PI)) / 2d);
        return from.lerp(to, progress);
    }

    // ------------------------------------------------------------- derived

    /**
     * @return green at full, amber in the middle, red when nearly gone
     *
     * <pre>
     * hue = (current / max) / 3     where hue 0 is red and ⅓ is green
     * </pre>
     *
     * <p>Interpolated through hue rather than between two RGB colours, which
     * avoids the muddy brown a straight red-to-green blend passes through at the
     * halfway point &mdash; exactly the value a health bar sits at most often.
     */
    public static Color health(double current, double max) {
        float fraction = max <= 0d ? 0f : MathUtil.saturate((float) (current / max));
        // Hue 0 is red, 1/3 is green; scale the fraction into that arc.
        return Color.hsb(fraction / 3f, 0.85f, 0.95f);
    }

    /**
     * @return perceived brightness of a colour, 0..1
     *
     * <p>The Rec. 709 luma coefficients:
     *
     * <pre>
     * Y = 0.2126·R + 0.7152·G + 0.0722·B      each channel 0..1
     * </pre>
     *
     * <p>Weighted for the eye, not the maths: green looks far brighter than blue
     * at the same numeric value, so an unweighted average calls a saturated blue
     * light and produces unreadable white text on it.
     */
    public static float luminance(Color color) {
        return 0.2126f * color.redF() + 0.7152f * color.greenF() + 0.0722f * color.blueF();
    }

    /** @return black or white, whichever stays legible on {@code background}. */
    public static Color readableOn(Color background) {
        return luminance(background) > 0.55f ? Color.BLACK : Color.WHITE;
    }

    /**
     * @return the mean of several colours, alpha included. Empty input gives transparent
     *
     * <p>Rounds rather than truncates, so blending black and white lands on the
     * same grey {@link Color#lerp} produces at the halfway point. An off-by-one
     * between two ways of mixing the same colours shows up as a visible seam
     * where a gradient meets an averaged fill.
     */
    public static Color average(Color... colors) {
        if (colors == null || colors.length == 0) {
            return Color.TRANSPARENT;
        }
        int red = 0;
        int green = 0;
        int blue = 0;
        int alpha = 0;
        for (Color color : colors) {
            red += color.getRed();
            green += color.getGreen();
            blue += color.getBlue();
            alpha += color.getAlpha();
        }
        int count = colors.length;
        return Color.of(Math.round(red / (float) count), Math.round(green / (float) count),
                Math.round(blue / (float) count), Math.round(alpha / (float) count));
    }

    // ----------------------------------------------------------------- HSB

    /**
     * @return {@code {hue, saturation, brightness}}, each 0..1
     *
     * <p>The inverse of {@link Color#hsb}, which only ever went one way. Needed to
     * modify a colour a user picked: shifting a theme's hue means reading the hue
     * it currently has.
     */
    public static float[] toHsb(Color color) {
        float red = color.redF();
        float green = color.greenF();
        float blue = color.blueF();
        float max = Math.max(red, Math.max(green, blue));
        float min = Math.min(red, Math.min(green, blue));
        float delta = max - min;

        float hue = 0f;
        if (delta > 0f) {
            if (max == red) {
                hue = ((green - blue) / delta) / 6f;
            } else if (max == green) {
                hue = (2f + (blue - red) / delta) / 6f;
            } else {
                hue = (4f + (red - green) / delta) / 6f;
            }
            hue -= (float) Math.floor(hue);
        }
        return new float[] { hue, max == 0f ? 0f : delta / max, max };
    }

    public static float hue(Color color) {
        return toHsb(color)[0];
    }

    public static float saturation(Color color) {
        return toHsb(color)[1];
    }

    public static float brightness(Color color) {
        return toHsb(color)[2];
    }

    /** @return the same colour at a different hue, keeping saturation, brightness and alpha. */
    public static Color withHue(Color color, float hue) {
        float[] hsb = toHsb(color);
        return Color.hsb(hue, hsb[1], hsb[2], color.alphaF());
    }

    /** @return the colour rotated {@code amount} of a turn around the colour wheel. */
    public static Color shiftHue(Color color, float amount) {
        float[] hsb = toHsb(color);
        return Color.hsb(hsb[0] + amount, hsb[1], hsb[2], color.alphaF());
    }

    /** @return the colour with its saturation replaced. 0 is greyscale. */
    public static Color withSaturation(Color color, float saturation) {
        float[] hsb = toHsb(color);
        return Color.hsb(hsb[0], MathUtil.saturate(saturation), hsb[2], color.alphaF());
    }

    /** @return the colour drained of hue, preserving perceived brightness. */
    public static Color greyscale(Color color) {
        int level = Math.round(luminance(color) * 255f);
        return Color.of(level, level, level, color.getAlpha());
    }

    // ------------------------------------------------------------ packing

    /**
     * @return the colour packed as 0xRRGGBBAA
     *
     * <p>{@link Color#toARGB()} is the form the game's own draw calls want. Most
     * standalone libraries &mdash; NanoVG, OpenGL vertex colours &mdash; want the
     * alpha last instead, and swapping the bytes at every call site is how one
     * call site ends up doing it the other way round.
     */
    public static int toRGBA(Color color) {
        return (color.getRed() << 24) | (color.getGreen() << 16) | (color.getBlue() << 8) | color.getAlpha();
    }

    /** @return a colour read from a packed 0xRRGGBBAA integer. */
    public static Color fromRGBA(int packed) {
        return Color.of((packed >> 24) & 0xFF, (packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF);
    }

    /** @return the four components as 0..1 floats in RGBA order, for a shader uniform. */
    public static float[] toFloats(Color color) {
        return new float[] { color.redF(), color.greenF(), color.blueF(), color.alphaF() };
    }
}
