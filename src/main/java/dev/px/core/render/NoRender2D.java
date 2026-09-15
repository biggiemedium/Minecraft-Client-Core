package dev.px.core.render;

import dev.px.core.render.font.Font;

/**
 * The 2D backend installed before a real one is.
 *
 * <p>Drawing calls are dropped rather than throwing. A module that renders
 * during early startup, a unit test, or a headless run then behaves sensibly
 * instead of taking the client down; {@link Render#has2D()} distinguishes this
 * from a working backend when a caller needs to know.
 */
final class NoRender2D implements Render2D {

    static final NoRender2D INSTANCE = new NoRender2D();

    private NoRender2D() {
    }

    @Override public void beginFrame(float width, float height, float scale) { }

    @Override public void endFrame() { }

    @Override public void rect(float x, float y, float width, float height, Color color) { }

    @Override public void gradientRect(float x, float y, float width, float height,
                                       Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) { }

    @Override public void roundRect(float x, float y, float width, float height, float radius, Color color) { }

    @Override public void roundGradient(float x, float y, float width, float height, float radius,
                                        Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) { }

    @Override public void rectOutline(float x, float y, float width, float height,
                                      float thickness, Color color) { }

    @Override public void roundRectOutline(float x, float y, float width, float height,
                                           float radius, float thickness, Color color) { }

    @Override public void line(float x1, float y1, float x2, float y2, float thickness, Color color) { }

    @Override public void circle(float centerX, float centerY, float radius, Color color) { }

    @Override public void circleOutline(float centerX, float centerY, float radius,
                                        float thickness, Color color) { }

    @Override public void arc(float centerX, float centerY, float radius,
                              float startAngle, float endAngle, float thickness, Color color) { }

    @Override public void triangle(float x1, float y1, float x2, float y2,
                                   float x3, float y3, Color color) { }

    @Override public void text(Font font, String value, float x, float y, Color color) { }

    @Override public void textShadowed(Font font, String value, float x, float y,
                                       Color color, Color shadowColor) { }

    @Override public void texture(Texture texture, float x, float y, float width, float height, Color tint) { }

    @Override public void roundTexture(Texture texture, float x, float y, float width, float height,
                                       float radius, Color tint) { }

    @Override public void blur(float x, float y, float width, float height, float radius) { }

    @Override public void shadow(float x, float y, float width, float height, float radius, Color color) { }

    @Override public boolean supportsBlur() {
        return false;
    }

    @Override public void pushClip(float x, float y, float width, float height) { }

    @Override public void popClip() { }

    @Override public void pushTransform() { }

    @Override public void popTransform() { }

    @Override public void translate(float x, float y) { }

    @Override public void scale(float x, float y) { }

    @Override public void rotate(float degrees) { }

    @Override public void pushAlpha(float alpha) { }

    @Override public void popAlpha() { }
}
