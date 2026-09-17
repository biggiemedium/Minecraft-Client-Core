package dev.px.core.test.example;

import dev.px.core.hud.AbstractHudElement;
import dev.px.core.hud.Anchor;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudLayout;
import dev.px.core.layout.Shape;
import dev.px.core.math.Vec2;
import dev.px.core.render.Color;
import dev.px.core.render.Render;

/**
 * A polygonal element: a forward-facing radar cone.
 *
 * <p>Proves the shape system is not limited to rectangles and circles. The cone
 * is a triangle, so the empty space either side of it is not clickable and a
 * selection outline traces the triangle.
 */
public final class ExampleRadar extends AbstractHudElement {

    private static final float WIDTH = 60f;
    private static final float HEIGHT = 40f;

    public ExampleRadar() {
        super("radar", "Radar Cone", HudLayout.at(Anchor.BOTTOM_CENTER, 0f, -8f));
    }

    @Override
    public void content(Content c) {
        c.custom(WIDTH, HEIGHT, (x, y, w, h) -> Render.triangle(
                x + w / 2f, y, x, y + h, x + w, y + h, Color.of(80, 200, 255, 90)));
    }

    @Override
    public Shape getShape(float x, float y, float w, float h) {
        return Shape.polygon(
                Vec2.of(x + w / 2f, y),
                Vec2.of(x, y + h),
                Vec2.of(x + w, y + h));
    }
}
