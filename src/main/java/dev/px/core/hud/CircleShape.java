package dev.px.core.hud;

import dev.px.core.render.Color;
import dev.px.core.render.Render;
import lombok.RequiredArgsConstructor;

/** A circle. Its bounding box is square, but only the disc responds to clicks. */
@RequiredArgsConstructor
final class CircleShape implements Shape {

    private final float centerX;
    private final float centerY;
    private final float radius;

    @Override
    public boolean contains(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        return dx * dx + dy * dy <= radius * radius;
    }

    @Override
    public Bounds bounds() {
        return Bounds.of(centerX - radius, centerY - radius, radius * 2f, radius * 2f);
    }

    @Override
    public void stroke(float thickness, Color color) {
        Render.circleOutline(centerX, centerY, radius, thickness, color);
    }

    @Override
    public Shape scaled(float originX, float originY, float factor) {
        return new CircleShape(
                originX + (centerX - originX) * factor,
                originY + (centerY - originY) * factor,
                radius * factor);
    }
}
