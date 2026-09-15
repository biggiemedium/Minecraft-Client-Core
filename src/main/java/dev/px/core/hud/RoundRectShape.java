package dev.px.core.hud;

import dev.px.core.render.Color;
import dev.px.core.render.Render;
import lombok.RequiredArgsConstructor;

/**
 * A rectangle with rounded corners.
 *
 * <p>Containment genuinely excludes the corners rather than approximating with
 * the bounding box, which is the whole reason this type exists separately from
 * {@link RectShape}.
 */
@RequiredArgsConstructor
final class RoundRectShape implements Shape {

    private final Bounds bounds;
    private final float radius;

    @Override
    public boolean contains(float x, float y) {
        if (!bounds.contains(x, y)) {
            return false;
        }
        // A radius bigger than half the shorter side would make the corner arcs
        // overlap; clamping keeps the test consistent with what the backend draws.
        float r = Math.min(radius, Math.min(bounds.getWidth(), bounds.getHeight()) / 2f);
        if (r <= 0f) {
            return true;
        }
        // Distance from the nearest corner centre, but only in the corner quadrants.
        float cx = clamp(x, bounds.getX() + r, bounds.getRight() - r);
        float cy = clamp(y, bounds.getY() + r, bounds.getBottom() - r);
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

    @Override
    public void stroke(float thickness, Color color) {
        Render.roundRectOutline(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(),
                radius, thickness, color);
    }

    @Override
    public Shape scaled(float originX, float originY, float factor) {
        return new RoundRectShape(bounds.scaled(originX, originY, factor), radius * factor);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }
}
