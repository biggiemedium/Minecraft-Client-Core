package dev.px.core.render;

/**
 * Immutable packed ARGB colour.
 *
 * <p>Core defines its own instead of using {@code java.awt.Color} so nothing in
 * the render path drags AWT in, and so a colour can be handed to a NanoVG,
 * Skija, or OpenGL backend without conversion at every call site.
 *
 * <p>Instances are cheap and escape-analysis friendly; treat them as values.
 */
public final class Color {

    public static final Color WHITE = rgb(0xFFFFFF);
    public static final Color BLACK = rgb(0x000000);
    public static final Color RED = rgb(0xFF5555);
    public static final Color GREEN = rgb(0x55FF55);
    public static final Color BLUE = rgb(0x5555FF);
    public static final Color YELLOW = rgb(0xFFFF55);
    public static final Color ORANGE = rgb(0xFFAA00);
    public static final Color CYAN = rgb(0x55FFFF);
    public static final Color MAGENTA = rgb(0xFF55FF);
    public static final Color GRAY = rgb(0xAAAAAA);
    public static final Color DARK_GRAY = rgb(0x555555);
    public static final Color TRANSPARENT = argb(0x00000000);

    private final int packed;

    private Color(int packed) {
        this.packed = packed;
    }

    // ------------------------------------------------------------ factories

    /** From a packed 0xAARRGGBB integer. */
    public static Color argb(int packed) {
        return new Color(packed);
    }

    /** From a packed 0xRRGGBB integer, fully opaque. */
    public static Color rgb(int packed) {
        return new Color(0xFF000000 | packed);
    }

    public static Color of(int red, int green, int blue) {
        return of(red, green, blue, 255);
    }

    public static Color of(int red, int green, int blue, int alpha) {
        return new Color((clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue));
    }

    /** From 0..1 floats, the form most shader uniforms want. */
    public static Color ofFloats(float red, float green, float blue, float alpha) {
        return of(Math.round(red * 255f), Math.round(green * 255f),
                Math.round(blue * 255f), Math.round(alpha * 255f));
    }

    /**
     * From hue/saturation/brightness, all 0..1. Hue wraps, so a rainbow is just
     * {@code hsb(time % 1f, 1f, 1f)}.
     */
    public static Color hsb(float hue, float saturation, float brightness) {
        return hsb(hue, saturation, brightness, 1f);
    }

    public static Color hsb(float hue, float saturation, float brightness, float alpha) {
        hue = hue - (float) Math.floor(hue);
        int sector = (int) (hue * 6f);
        float fraction = hue * 6f - sector;
        float p = brightness * (1f - saturation);
        float q = brightness * (1f - fraction * saturation);
        float t = brightness * (1f - (1f - fraction) * saturation);
        switch (sector) {
            case 0: return ofFloats(brightness, t, p, alpha);
            case 1: return ofFloats(q, brightness, p, alpha);
            case 2: return ofFloats(p, brightness, t, alpha);
            case 3: return ofFloats(p, q, brightness, alpha);
            case 4: return ofFloats(t, p, brightness, alpha);
            default: return ofFloats(brightness, p, q, alpha);
        }
    }

    /** Parses {@code #RRGGBB}, {@code #AARRGGBB}, or either without the hash. */
    public static Color parse(String hex) {
        String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
        long value = Long.parseLong(cleaned, 16);
        return cleaned.length() <= 6 ? rgb((int) value) : argb((int) value);
    }

    // ------------------------------------------------------------ accessors

    public int toARGB() {
        return packed;
    }

    public int getRed() {
        return (packed >> 16) & 0xFF;
    }

    public int getGreen() {
        return (packed >> 8) & 0xFF;
    }

    public int getBlue() {
        return packed & 0xFF;
    }

    public int getAlpha() {
        return (packed >>> 24) & 0xFF;
    }

    public float redF() {
        return getRed() / 255f;
    }

    public float greenF() {
        return getGreen() / 255f;
    }

    public float blueF() {
        return getBlue() / 255f;
    }

    public float alphaF() {
        return getAlpha() / 255f;
    }

    // ------------------------------------------------------------ transforms

    public Color withAlpha(int alpha) {
        return new Color((clamp(alpha) << 24) | (packed & 0x00FFFFFF));
    }

    public Color withAlpha(float alpha) {
        return withAlpha(Math.round(alpha * 255f));
    }

    /** Multiplies the existing alpha, for fading something already translucent. */
    public Color fade(float factor) {
        return withAlpha(Math.round(getAlpha() * factor));
    }

    public Color brighter(float factor) {
        return of(Math.round(getRed() * factor), Math.round(getGreen() * factor),
                Math.round(getBlue() * factor), getAlpha());
    }

    public Color darker(float factor) {
        return brighter(1f - factor);
    }

    /** Linear blend towards {@code target}; {@code progress} 0 returns this colour. */
    public Color lerp(Color target, float progress) {
        float clamped = progress < 0f ? 0f : progress > 1f ? 1f : progress;
        return of(
                Math.round(getRed() + (target.getRed() - getRed()) * clamped),
                Math.round(getGreen() + (target.getGreen() - getGreen()) * clamped),
                Math.round(getBlue() + (target.getBlue() - getBlue()) * clamped),
                Math.round(getAlpha() + (target.getAlpha() - getAlpha()) * clamped));
    }

    /** @return {@code #AARRGGBB}, the form {@link #parse} reads back. */
    public String toHex() {
        return String.format("#%08X", packed);
    }

    private static int clamp(int component) {
        return component < 0 ? 0 : component > 255 ? 255 : component;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Color && ((Color) other).packed == packed;
    }

    @Override
    public int hashCode() {
        return packed;
    }

    @Override
    public String toString() {
        return toHex();
    }
}
