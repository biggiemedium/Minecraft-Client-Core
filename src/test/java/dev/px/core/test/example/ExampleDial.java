package dev.px.core.test.example;

import dev.px.core.hud.AbstractHudElement;
import dev.px.core.hud.Anchor;
import dev.px.core.hud.HudLayout;
import dev.px.core.hud.Shape;
import dev.px.core.hud.Size;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.NumberSetting;
import lombok.Getter;

/**
 * A circular element: a speedometer-style dial.
 *
 * <p>The reason {@link Shape} exists. Its layout box is square, but clicking the
 * corner of that square should miss the dial, and the editor's selection outline
 * should be a circle rather than a rectangle. Overriding {@link #getShape} gives
 * both, because the editor asks the shape to stroke itself rather than assuming
 * a rectangle.
 */
@Getter
public final class ExampleDial extends AbstractHudElement {

    private final NumberSetting<Integer> diameter = integer("Diameter", 40, 20, 120);

    public ExampleDial() {
        super("dial", "Speed Dial", HudLayout.at(Anchor.BOTTOM_LEFT, 8f, -8f));
    }

    @Override
    public Size getPreferredSize() {
        return Size.of(diameter.getFloat(), diameter.getFloat());
    }

    @Override
    public void render(float x, float y, float w, float h) {
        float radius = w / 2f;
        Render.circle(x + radius, y + radius, radius, Color.of(0, 0, 0, 120));
        Render.circleOutline(x + radius, y + radius, radius, 1f, Color.WHITE);
        // A needle, drawn edge-on for simplicity.
        Render.line(x + radius, y + radius, x + w, y + radius, 1.5f, Color.RED);
    }

    @Override
    public Shape getShape(float x, float y, float w, float h) {
        return Shape.circle(x + w / 2f, y + h / 2f, w / 2f);
    }
}
