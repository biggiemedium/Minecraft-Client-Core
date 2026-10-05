package dev.px.core.layout;

import dev.px.core.render.Color;
import dev.px.core.render.Render;
import lombok.RequiredArgsConstructor;

/** The default shape: interaction region equals layout bounds. */
@RequiredArgsConstructor
final class RectShape implements Shape {

    private final Bounds bounds;

    @Override
    public boolean contains(float x, float y) {
        return bounds.contains(x, y);
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

    @Override
    public void stroke(float thickness, Color color) {
        Render.rectOutline(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(),
                thickness, color);
    }

    @Override
    public Shape scaled(float originX, float originY, float factor) {
        return new RectShape(bounds.scaled(originX, originY, factor));
    }
}
