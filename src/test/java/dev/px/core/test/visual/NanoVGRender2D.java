package dev.px.core.test.visual;

import dev.px.core.render.Color;
import dev.px.core.render.Render2D;
import dev.px.core.render.Texture;
import dev.px.core.render.font.Font;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * A real 2D backend, on NanoVG.
 *
 * <p>The proof that Core's render seam is the right size. Every drawing call the
 * HUD makes lands here, and there is nothing in this file that knows what a HUD
 * element is &mdash; it is 28 methods of "draw this shape", which is exactly what
 * a version adapter would write once and never touch again.
 *
 * <p>Swapping this for Skija or a raw GL backend is a one-line change at the
 * {@code Render.install} call. Nothing above it moves.
 */
public final class NanoVGRender2D implements Render2D {

    private final long vg;

    /** Reused rather than allocated per call: these are native structs. */
    private final NVGColor fill = NVGColor.create();
    private final NVGColor stroke = NVGColor.create();
    private final NVGPaint paint = NVGPaint.create();

    /** NanoVG has one global alpha, so nesting has to be tracked here. */
    private final Deque<Float> alphas = new ArrayDeque<>();
    private float alpha = 1f;

    private float pixelRatio = 1f;

    public NanoVGRender2D(long vg) {
        this.vg = vg;
    }

    public long handle() {
        return vg;
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void beginFrame(float width, float height, float scale) {
        this.pixelRatio = scale;
        alphas.clear();
        alpha = 1f;
        nvgBeginFrame(vg, width, height, scale);
        nvgGlobalAlpha(vg, 1f);
    }

    @Override
    public void endFrame() {
        nvgEndFrame(vg);
    }

    public float getPixelRatio() {
        return pixelRatio;
    }

    // ----------------------------------------------------------------- shapes

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, width, height);
        fill(color);
    }

    @Override
    public void roundRect(float x, float y, float width, float height, float radius, Color color) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, radius);
        fill(color);
    }

    @Override
    public void gradientRect(float x, float y, float width, float height,
                             Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) {
        // NanoVG has two-stop gradients, so a four-corner blend is approximated
        // by its vertical average. Good enough for a harness.
        nvgBeginPath(vg);
        nvgRect(vg, x, y, width, height);
        nvgFillPaint(vg, nvgLinearGradient(vg, x, y, x, y + height,
                colorInto(topLeft.lerp(topRight, 0.5f), fill),
                colorInto(bottomLeft.lerp(bottomRight, 0.5f), stroke), paint));
        nvgFill(vg);
    }

    @Override
    public void roundGradient(float x, float y, float width, float height, float radius,
                              Color topLeft, Color topRight, Color bottomRight, Color bottomLeft) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, radius);
        nvgFillPaint(vg, nvgLinearGradient(vg, x, y, x, y + height,
                colorInto(topLeft.lerp(topRight, 0.5f), fill),
                colorInto(bottomLeft.lerp(bottomRight, 0.5f), stroke), paint));
        nvgFill(vg);
    }

    @Override
    public void rectOutline(float x, float y, float width, float height, float thickness, Color color) {
        float inset = thickness / 2f;
        nvgBeginPath(vg);
        nvgRect(vg, x + inset, y + inset, width - thickness, height - thickness);
        strokeWith(color, thickness);
    }

    @Override
    public void roundRectOutline(float x, float y, float width, float height,
                                 float radius, float thickness, Color color) {
        float inset = thickness / 2f;
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + inset, y + inset, width - thickness, height - thickness, radius);
        strokeWith(color, thickness);
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float thickness, Color color) {
        nvgBeginPath(vg);
        nvgMoveTo(vg, x1, y1);
        nvgLineTo(vg, x2, y2);
        strokeWith(color, thickness);
    }

    @Override
    public void circle(float centerX, float centerY, float radius, Color color) {
        nvgBeginPath(vg);
        nvgCircle(vg, centerX, centerY, radius);
        fill(color);
    }

    @Override
    public void circleOutline(float centerX, float centerY, float radius, float thickness, Color color) {
        nvgBeginPath(vg);
        nvgCircle(vg, centerX, centerY, radius);
        strokeWith(color, thickness);
    }

    @Override
    public void arc(float centerX, float centerY, float radius,
                    float startAngle, float endAngle, float thickness, Color color) {
        nvgBeginPath(vg);
        nvgArc(vg, centerX, centerY, radius,
                (float) Math.toRadians(startAngle), (float) Math.toRadians(endAngle), NVG_CW);
        strokeWith(color, thickness);
    }

    @Override
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Color color) {
        nvgBeginPath(vg);
        nvgMoveTo(vg, x1, y1);
        nvgLineTo(vg, x2, y2);
        nvgLineTo(vg, x3, y3);
        nvgClosePath(vg);
        fill(color);
    }

    // ------------------------------------------------------------------- text

    @Override
    public void text(Font font, String value, float x, float y, Color color) {
        apply(font);
        nvgFillColor(vg, colorInto(color, fill));
        nvgText(vg, x, y, value);
    }

    @Override
    public void textShadowed(Font font, String value, float x, float y, Color color, Color shadowColor) {
        apply(font);
        nvgFillColor(vg, colorInto(shadowColor, fill));
        nvgText(vg, x + 1f, y + 1f, value);
        nvgFillColor(vg, colorInto(color, fill));
        nvgText(vg, x, y, value);
    }

    /** Text is anchored top-left, which is the corner every Core API talks about. */
    private void apply(Font font) {
        NanoVGFont resolved = font instanceof NanoVGFont ? (NanoVGFont) font : null;
        if (resolved != null) {
            nvgFontFaceId(vg, resolved.getHandle());
            nvgFontSize(vg, resolved.getSize());
        }
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
    }

    // --------------------------------------------------------------- textures

    @Override
    public void texture(Texture texture, float x, float y, float width, float height, Color tint) {
        // The harness draws no textures; a real adapter would bind an image here.
        rect(x, y, width, height, tint);
    }

    @Override
    public void roundTexture(Texture texture, float x, float y, float width, float height,
                             float radius, Color tint) {
        roundRect(x, y, width, height, radius, tint);
    }

    // ---------------------------------------------------------------- effects

    @Override
    public void blur(float x, float y, float width, float height, float radius) {
        // NanoVG cannot read back the framebuffer, so this is a no-op and
        // supportsBlur() says so rather than pretending.
    }

    @Override
    public void shadow(float x, float y, float width, float height, float radius, Color color) {
        nvgBeginPath(vg);
        nvgRect(vg, x - radius, y - radius, width + radius * 2f, height + radius * 2f);
        nvgRoundedRect(vg, x, y, width, height, radius);
        nvgPathWinding(vg, NVG_HOLE);
        nvgFillPaint(vg, nvgBoxGradient(vg, x, y, width, height, radius, radius,
                colorInto(color, fill), colorInto(color.withAlpha(0), stroke), paint));
        nvgFill(vg);
    }

    @Override
    public boolean supportsBlur() {
        return false;
    }

    // ------------------------------------------------------------------ state

    @Override
    public void pushClip(float x, float y, float width, float height) {
        nvgSave(vg);
        nvgIntersectScissor(vg, x, y, width, height);
    }

    @Override
    public void popClip() {
        nvgRestore(vg);
    }

    @Override
    public void pushTransform() {
        nvgSave(vg);
    }

    @Override
    public void popTransform() {
        nvgRestore(vg);
    }

    @Override
    public void translate(float x, float y) {
        nvgTranslate(vg, x, y);
    }

    @Override
    public void scale(float x, float y) {
        nvgScale(vg, x, y);
    }

    @Override
    public void rotate(float degrees) {
        nvgRotate(vg, (float) Math.toRadians(degrees));
    }

    @Override
    public void pushAlpha(float value) {
        alphas.push(alpha);
        alpha *= value;
        nvgGlobalAlpha(vg, alpha);
    }

    @Override
    public void popAlpha() {
        alpha = alphas.isEmpty() ? 1f : alphas.pop();
        nvgGlobalAlpha(vg, alpha);
    }

    // --------------------------------------------------------------- internals

    private void fill(Color color) {
        nvgFillColor(vg, colorInto(color, fill));
        nvgFill(vg);
    }

    private void strokeWith(Color color, float thickness) {
        nvgStrokeColor(vg, colorInto(color, stroke));
        nvgStrokeWidth(vg, thickness);
        nvgStroke(vg);
    }

    private static NVGColor colorInto(Color color, NVGColor target) {
        target.r(color.redF());
        target.g(color.greenF());
        target.b(color.blueF());
        target.a(color.alphaF());
        return target;
    }
}
