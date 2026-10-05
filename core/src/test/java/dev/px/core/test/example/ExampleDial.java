package dev.px.core.test.example;

import dev.px.core.hud.AbstractHudElement;
import dev.px.core.hud.Anchor;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudLayout;
import dev.px.core.layout.Shape;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.NumberSetting;
import lombok.Getter;

/**
 * A circular element: a speedometer-style dial.
 *
 * <p>Two things at once.
 *
 * <p>The first is {@link Shape}: the dial's layout box is square, but clicking
 * the corner of that square should miss it, and a selection outline should be a
 * circle. Overriding {@link #getShape} gives both, and a {@code Shape} strokes
 * itself, so a client's editor outlines this correctly without ever switching on
 * shape type.
 *
 * <p>The second is {@link Content#custom}: rows and padding cannot express a
 * dial, so it drops to a raw drawing callback and gives up nothing. It is still
 * measured, anchored, clamped, scaled and hit tested like everything else &mdash;
 * the escape hatch costs only the layout help, not the layout engine.
 */
@Getter
public final class ExampleDial extends AbstractHudElement {

    private final NumberSetting<Integer> diameter = integer("Diameter", 40, 20, 120);

    public ExampleDial() {
        super("dial", "Speed Dial", HudLayout.at(Anchor.BOTTOM_LEFT, 8f, -8f));
    }

    @Override
    public void content(Content c) {
        float size = diameter.getFloat();
        c.custom(size, size, (x, y, w, h) -> {
            float radius = w / 2f;
            Render.circle(x + radius, y + radius, radius, Color.of(0, 0, 0, 120));
            Render.circleOutline(x + radius, y + radius, radius, 1f, Color.WHITE);
            // A needle, drawn edge-on for simplicity.
            Render.line(x + radius, y + radius, x + w, y + radius, 1.5f, Color.RED);
        });
    }

    @Override
    public Shape getShape(float x, float y, float w, float h) {
        return Shape.circle(x + w / 2f, y + h / 2f, w / 2f);
    }
}
