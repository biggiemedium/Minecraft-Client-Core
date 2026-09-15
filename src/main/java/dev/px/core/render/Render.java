package dev.px.core.render;

import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.render.font.Font;

/**
 * The global drawing entry point.
 *
 * <p>Every module, HUD element and GUI component draws through these static
 * methods. Which library actually executes them is decided once, at startup, by
 * whichever {@link Render2D} and {@link Render3D} the adapter installs, so
 * swapping NanoVG for Skija is a one-line change that touches no drawing code.
 *
 * <pre>{@code
 * Render.roundRect(x, y, w, h, 4, theme.getSurface());
 * Render.text("Kill Aura", x + 4, y + 3, Color.WHITE);
 * Render.boxOutline(target.getHitbox(), 1.5f, Color.RED);
 * }</pre>
 *
 * <p>Before a backend is installed every call is a silent no-op rather than a
 * crash, so a module that draws during early startup is harmless.
 */
public final class Render {

    private static Render2D backend2D = NoRender2D.INSTANCE;
    private static Render3D backend3D = NoRender3D.INSTANCE;
    private static Font defaultFont;

    private Render() {
    }

    // -------------------------------------------------------------- wiring

    public static void install(Render2D backend) {
        backend2D = backend == null ? NoRender2D.INSTANCE : backend;
    }

    public static void install(Render3D backend) {
        backend3D = backend == null ? NoRender3D.INSTANCE : backend;
    }

    public static void setDefaultFont(Font font) {
        defaultFont = font;
    }

    public static Font getDefaultFont() {
        return defaultFont;
    }

    /** @return whether a real 2D backend is installed. */
    public static boolean has2D() {
        return backend2D != NoRender2D.INSTANCE;
    }

    /** @return whether a real 3D backend is installed. */
    public static boolean has3D() {
        return backend3D != NoRender3D.INSTANCE;
    }

    /** Escape hatch for a backend-specific feature the facade does not expose. */
    public static Render2D backend2D() {
        return backend2D;
    }

    public static Render3D backend3D() {
        return backend3D;
    }

    // --------------------------------------------------------------- frame

    public static void begin2D(float width, float height, float scale) {
        backend2D.beginFrame(width, height, scale);
    }

    public static void end2D() {
        backend2D.endFrame();
    }

    public static void begin3D(float partialTicks) {
        backend3D.beginFrame(partialTicks);
    }

    public static void end3D() {
        backend3D.endFrame();
    }

    // --------------------------------------------------------- 2D: shapes

    public static void rect(float x, float y, float width, float height, Color color) {
        backend2D.rect(x, y, width, height, color);
    }

    public static void gradientRect(float x, float y, float width, float height,
                                    Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) {
        backend2D.gradientRect(x, y, width, height, topLeft, topRight, bottomRight, bottomLeft);
    }

    public static void gradientH(float x, float y, float width, float height, Color left, Color right) {
        backend2D.gradientRect(x, y, width, height, left, right, right, left);
    }

    public static void gradientV(float x, float y, float width, float height, Color top, Color bottom) {
        backend2D.gradientRect(x, y, width, height, top, top, bottom, bottom);
    }

    public static void roundRect(float x, float y, float width, float height, float radius, Color color) {
        backend2D.roundRect(x, y, width, height, radius, color);
    }

    public static void roundGradient(float x, float y, float width, float height, float radius,
                                     Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) {
        backend2D.roundGradient(x, y, width, height, radius, topLeft, topRight, bottomRight, bottomLeft);
    }

    public static void rectOutline(float x, float y, float width, float height, float thickness, Color color) {
        backend2D.rectOutline(x, y, width, height, thickness, color);
    }

    public static void roundRectOutline(float x, float y, float width, float height,
                                        float radius, float thickness, Color color) {
        backend2D.roundRectOutline(x, y, width, height, radius, thickness, color);
    }

    public static void line(float x1, float y1, float x2, float y2, float thickness, Color color) {
        backend2D.line(x1, y1, x2, y2, thickness, color);
    }

    public static void circle(float centerX, float centerY, float radius, Color color) {
        backend2D.circle(centerX, centerY, radius, color);
    }

    public static void circleOutline(float centerX, float centerY, float radius, float thickness, Color color) {
        backend2D.circleOutline(centerX, centerY, radius, thickness, color);
    }

    public static void arc(float centerX, float centerY, float radius,
                           float startAngle, float endAngle, float thickness, Color color) {
        backend2D.arc(centerX, centerY, radius, startAngle, endAngle, thickness, color);
    }

    public static void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Color color) {
        backend2D.triangle(x1, y1, x2, y2, x3, y3, color);
    }

    /** A horizontal progress bar. Common enough in HUD code to be worth a method. */
    public static void progressBar(float x, float y, float width, float height, float radius,
                                   float progress, Color track, Color fill) {
        backend2D.roundRect(x, y, width, height, radius, track);
        float filled = width * Math.max(0f, Math.min(1f, progress));
        if (filled > 0f) {
            backend2D.roundRect(x, y, filled, height, Math.min(radius, filled / 2f), fill);
        }
    }

    // ----------------------------------------------------------- 2D: text

    public static void text(String value, float x, float y, Color color) {
        backend2D.text(requireFont(), value, x, y, color);
    }

    public static void text(Font font, String value, float x, float y, Color color) {
        backend2D.text(font, value, x, y, color);
    }

    public static void textShadowed(String value, float x, float y, Color color) {
        backend2D.textShadowed(requireFont(), value, x, y, color, Color.of(0, 0, 0, 160));
    }

    public static void textShadowed(Font font, String value, float x, float y, Color color, Color shadow) {
        backend2D.textShadowed(font, value, x, y, color, shadow);
    }

    /** Draws text anchored at {@code x} according to {@code alignment}. */
    public static void text(Font font, String value, float x, float y, Alignment alignment, Color color) {
        backend2D.text(font, value, x + alignment.offsetFor(font.widthOf(value)), y, color);
    }

    public static float textWidth(String value) {
        return requireFont().widthOf(value);
    }

    public static float textHeight() {
        return requireFont().getHeight();
    }

    // -------------------------------------------------------- 2D: textures

    public static void texture(Texture texture, float x, float y, float width, float height) {
        backend2D.texture(texture, x, y, width, height, Color.WHITE);
    }

    public static void texture(Texture texture, float x, float y, float width, float height, Color tint) {
        backend2D.texture(texture, x, y, width, height, tint);
    }

    public static void roundTexture(Texture texture, float x, float y, float width, float height,
                                    float radius, Color tint) {
        backend2D.roundTexture(texture, x, y, width, height, radius, tint);
    }

    // --------------------------------------------------------- 2D: effects

    public static void blur(float x, float y, float width, float height, float radius) {
        backend2D.blur(x, y, width, height, radius);
    }

    public static void shadow(float x, float y, float width, float height, float radius, Color color) {
        backend2D.shadow(x, y, width, height, radius, color);
    }

    public static boolean supportsBlur() {
        return backend2D.supportsBlur();
    }

    // ----------------------------------------------------------- 2D: state

    public static void pushClip(float x, float y, float width, float height) {
        backend2D.pushClip(x, y, width, height);
    }

    public static void popClip() {
        backend2D.popClip();
    }

    public static void pushTransform() {
        backend2D.pushTransform();
    }

    public static void popTransform() {
        backend2D.popTransform();
    }

    public static void translate(float x, float y) {
        backend2D.translate(x, y);
    }

    public static void scale(float x, float y) {
        backend2D.scale(x, y);
    }

    public static void rotate(float degrees) {
        backend2D.rotate(degrees);
    }

    public static void pushAlpha(float alpha) {
        backend2D.pushAlpha(alpha);
    }

    public static void popAlpha() {
        backend2D.popAlpha();
    }

    /**
     * Scales everything drawn by {@code body} about the given origin.
     *
     * <p>The paired push and pop are easy to unbalance by hand, and an unbalanced
     * transform corrupts every later draw call in the frame. Passing the body in
     * makes that impossible.
     */
    public static void scaled(float originX, float originY, float factor, Runnable body) {
        backend2D.pushTransform();
        backend2D.translate(originX, originY);
        backend2D.scale(factor, factor);
        backend2D.translate(-originX, -originY);
        try {
            body.run();
        } finally {
            backend2D.popTransform();
        }
    }

    /** Clips everything drawn by {@code body} to the given rectangle. */
    public static void clipped(float x, float y, float width, float height, Runnable body) {
        backend2D.pushClip(x, y, width, height);
        try {
            body.run();
        } finally {
            backend2D.popClip();
        }
    }

    // -------------------------------------------------------------- 3D

    public static void box(Box box, Color color) {
        backend3D.box(box, color);
    }

    public static void boxOutline(Box box, float thickness, Color color) {
        backend3D.boxOutline(box, thickness, color);
    }

    public static void boxFilledOutline(Box box, Color fill, Color outline, float thickness) {
        backend3D.boxFilledOutline(box, fill, outline, thickness);
    }

    public static void line3D(Vec3 from, Vec3 to, float thickness, Color color) {
        backend3D.line(from, to, thickness, color);
    }

    public static void sphere(Vec3 center, float radius, Color color) {
        backend3D.sphere(center, radius, 16, color);
    }

    public static void sphere(Vec3 center, float radius, int segments, Color color) {
        backend3D.sphere(center, radius, segments, color);
    }

    public static void sphereOutline(Vec3 center, float radius, int segments, float thickness, Color color) {
        backend3D.sphereOutline(center, radius, segments, thickness, color);
    }

    public static void circle3D(Vec3 center, float radius, Color color) {
        backend3D.circle(center, radius, 32, color);
    }

    public static void circleOutline3D(Vec3 center, float radius, float thickness, Color color) {
        backend3D.circleOutline(center, radius, 32, thickness, color);
    }

    public static void cylinder(Vec3 base, float radius, float height, Color color) {
        backend3D.cylinder(base, radius, height, 24, color);
    }

    public static void tracer(Vec3 target, float thickness, Color color) {
        backend3D.tracer(target, thickness, color);
    }

    /** @return screen coordinates for a world position, or null if it is behind the camera. */
    public static Vec2 worldToScreen(Vec3 position) {
        return backend3D.worldToScreen(position);
    }

    public static Render3D.ScreenBounds project(Box box) {
        return backend3D.project(box);
    }

    public static Vec3 cameraPosition() {
        return backend3D.getCameraPosition();
    }

    public static void setDepthTest(boolean enabled) {
        backend3D.setDepthTest(enabled);
    }

    private static Font requireFont() {
        Font font = defaultFont;
        if (font == null) {
            throw new IllegalStateException("No default font set; install a FontProvider before drawing text");
        }
        return font;
    }
}
