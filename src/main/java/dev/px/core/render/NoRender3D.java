package dev.px.core.render;

import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;

/**
 * The world-space backend installed before a real one is.
 *
 * <p>Draws nothing. {@link #worldToScreen} returns null, which callers already
 * have to handle for points behind the camera, so no extra branch is needed for
 * the missing-backend case.
 */
final class NoRender3D implements Render3D {

    static final NoRender3D INSTANCE = new NoRender3D();

    private NoRender3D() {
    }

    @Override public void beginFrame(float partialTicks) { }

    @Override public void endFrame() { }

    @Override public void box(Box box, Color color) { }

    @Override public void boxOutline(Box box, float thickness, Color color) { }

    @Override public void boxFilledOutline(Box box, Color fill, Color outline, float thickness) { }

    @Override public void line(Vec3 from, Vec3 to, float thickness, Color color) { }

    @Override public void sphere(Vec3 center, float radius, int segments, Color color) { }

    @Override public void sphereOutline(Vec3 center, float radius, int segments,
                                        float thickness, Color color) { }

    @Override public void circle(Vec3 center, float radius, int segments, Color color) { }

    @Override public void circleOutline(Vec3 center, float radius, int segments,
                                        float thickness, Color color) { }

    @Override public void cylinder(Vec3 base, float radius, float height, int segments, Color color) { }

    @Override public void tracer(Vec3 target, float thickness, Color color) { }

    @Override public Vec2 worldToScreen(Vec3 position) {
        return null;
    }

    @Override public ScreenBounds project(Box box) {
        return null;
    }

    @Override public Vec3 getCameraPosition() {
        return Vec3.ZERO;
    }

    @Override public void setDepthTest(boolean enabled) { }
}
