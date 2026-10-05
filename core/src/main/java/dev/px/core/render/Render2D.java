package dev.px.core.render;

import dev.px.core.render.font.Font;

/**
 * The pluggable 2D drawing backend.
 *
 * <p>Implement this once per rendering library and Core, the GUI, and every HUD
 * element draw through it unchanged. NanoVG, Skija, and legacy immediate-mode
 * OpenGL all fit; nothing here assumes a graphics API.
 *
 * <p>Coordinates are in scaled screen space with the origin at the top left,
 * the same space the game reports mouse positions in. The backend is
 * responsible for converting to whatever its library wants.
 *
 * <p>Call sites use the {@link Render} facade rather than this interface. Only
 * the adapter that installs a backend ever sees it.
 */
public interface Render2D {

    /**
     * Opens a frame. Called once before any drawing each time the client renders.
     *
     * <p>Retained-mode libraries need this to begin a scene and to learn the
     * current framebuffer size; an immediate-mode backend can push GL state here
     * and pop it in {@link #endFrame()}.
     *
     * @param width scaled screen width
     * @param height scaled screen height
     * @param scale pixels per scaled unit, for backends that rasterise at native resolution
     */
    void beginFrame(float width, float height, float scale);

    void endFrame();

    // ------------------------------------------------------------- shapes

    void rect(float x, float y, float width, float height, Color color);

    /** Four-corner gradient. Pass the same colour twice for a linear gradient. */
    void gradientRect(float x, float y, float width, float height,
                      Color topLeft, Color topRight, Color bottomRight, Color bottomLeft);

    void roundRect(float x, float y, float width, float height, float radius, Color color);

    void roundGradient(float x, float y, float width, float height, float radius,
                       Color topLeft, Color topRight, Color bottomRight, Color bottomLeft);

    /** A stroked rectangle. The stroke sits inside the given bounds. */
    void rectOutline(float x, float y, float width, float height, float thickness, Color color);

    void roundRectOutline(float x, float y, float width, float height,
                          float radius, float thickness, Color color);

    void line(float x1, float y1, float x2, float y2, float thickness, Color color);

    void circle(float centerX, float centerY, float radius, Color color);

    void circleOutline(float centerX, float centerY, float radius, float thickness, Color color);

    /**
     * A stroked arc, angles in degrees clockwise from twelve o'clock.
     * Used for cooldown rings and circular progress.
     */
    void arc(float centerX, float centerY, float radius,
             float startAngle, float endAngle, float thickness, Color color);

    /** A filled triangle. Used for dropdown carets and radar markers. */
    void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Color color);

    // --------------------------------------------------------------- text

    void text(Font font, String value, float x, float y, Color color);

    /**
     * Draws text with a drop shadow.
     *
     * <p>Separate from {@link #text} because backends implement it very
     * differently: a blur-capable library does it properly, while a legacy
     * backend draws the string twice.
     */
    void textShadowed(Font font, String value, float x, float y, Color color, Color shadowColor);

    // ------------------------------------------------------------ textures

    /**
     * Draws a texture, tinted by {@code tint}.
     * Pass {@link Color#WHITE} for the untinted image.
     */
    void texture(Texture texture, float x, float y, float width, float height, Color tint);

    void roundTexture(Texture texture, float x, float y, float width, float height,
                      float radius, Color tint);

    // ------------------------------------------------------------- effects

    /**
     * Blurs what is already on screen within the given bounds.
     *
     * <p>Backends that cannot blur should leave the region untouched rather than
     * approximating it with a flat fill, so the caller can detect the difference
     * through {@link #supportsBlur()}.
     */
    void blur(float x, float y, float width, float height, float radius);

    /** A soft shadow cast outside the given bounds. */
    void shadow(float x, float y, float width, float height, float radius, Color color);

    default boolean supportsBlur() {
        return true;
    }

    // -------------------------------------------------------------- state

    /**
     * Restricts drawing to a rectangle until the matching {@link #popClip()}.
     * Nested clips intersect, which is what a scrolling panel inside a scrolling
     * panel needs.
     */
    void pushClip(float x, float y, float width, float height);

    void popClip();

    void pushTransform();

    void popTransform();

    void translate(float x, float y);

    void scale(float x, float y);

    /** Rotates about the current origin, in degrees clockwise. */
    void rotate(float degrees);

    /** Multiplies the alpha of everything drawn until the matching pop. */
    void pushAlpha(float alpha);

    void popAlpha();
}
