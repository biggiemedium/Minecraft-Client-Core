package dev.px.core.test.harness;

import dev.px.core.render.Color;
import dev.px.core.render.Render2D;
import dev.px.core.render.Texture;
import dev.px.core.render.font.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * A 2D backend that draws nothing and writes down the lines it was asked for.
 *
 * <p>The suite runs with no backend on purpose, so most drawing is unobservable
 * and does not need observing. One thing does: the silhouette an element's
 * outline traces, where the whole question is which lines are <em>absent</em>.
 * Asserting that from the outside needs something on the other end of
 * {@code Render.line}.
 *
 * <p>Install it, draw, read {@link #getLines()}, then put the old backend back.
 */
public final class RecordingRender2D implements Render2D {

    /** One recorded call: {@code x1, y1, x2, y2}. */
    private final List<float[]> lines = new ArrayList<>();

    public List<float[]> getLines() {
        return lines;
    }

    public void clear() {
        lines.clear();
    }

    /** @return whether a line was drawn along y between the two x positions, in either direction. */
    public boolean hasHorizontal(float y, float fromX, float toX) {
        for (float[] line : lines) {
            if (near(line[1], y) && near(line[3], y)
                    && near(Math.min(line[0], line[2]), Math.min(fromX, toX))
                    && near(Math.max(line[0], line[2]), Math.max(fromX, toX))) {
                return true;
            }
        }
        return false;
    }

    /** @return whether any line was drawn along the given horizontal. */
    public boolean hasAnyAt(float y) {
        for (float[] line : lines) {
            if (near(line[1], y) && near(line[3], y)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float thickness, Color color) {
        lines.add(new float[] { x1, y1, x2, y2 });
    }

    // ------------------------------------------------- everything else: no-ops

    @Override public void beginFrame(float width, float height, float scale) { }
    @Override public void endFrame() { }
    @Override public void rect(float x, float y, float width, float height, Color color) { }
    @Override public void gradientRect(float x, float y, float width, float height,
                                       Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) { }
    @Override public void roundRect(float x, float y, float width, float height, float radius, Color color) { }
    @Override public void roundGradient(float x, float y, float width, float height, float radius,
                                        Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) { }
    @Override public void rectOutline(float x, float y, float width, float height, float thickness, Color color) { }
    @Override public void roundRectOutline(float x, float y, float width, float height,
                                           float radius, float thickness, Color color) { }
    @Override public void circle(float centerX, float centerY, float radius, Color color) { }
    @Override public void circleOutline(float centerX, float centerY, float radius, float thickness, Color color) { }
    @Override public void arc(float centerX, float centerY, float radius,
                              float startAngle, float endAngle, float thickness, Color color) { }
    @Override public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Color color) { }
    @Override public void text(Font font, String value, float x, float y, Color color) { }
    @Override public void textShadowed(Font font, String value, float x, float y, Color color, Color shadowColor) { }
    @Override public void texture(Texture texture, float x, float y, float width, float height, Color tint) { }
    @Override public void roundTexture(Texture texture, float x, float y, float width, float height,
                                       float radius, Color tint) { }
    @Override public void blur(float x, float y, float width, float height, float radius) { }
    @Override public void shadow(float x, float y, float width, float height, float radius, Color color) { }
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
