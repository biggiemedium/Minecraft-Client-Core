package dev.px.core.test.example;

import dev.px.core.hud.Anchor;
import dev.px.core.hud.HudElement;
import dev.px.core.hud.HudLayout;
import dev.px.core.hud.Size;
import dev.px.core.render.Color;
import dev.px.core.render.Render;

/**
 * The floor: a HUD element with no settings, implementing the bare interface.
 *
 * <p>Two mandatory methods and nothing else. Anchoring, scaling, clamping,
 * z-order, hit testing, dragging and persistence are all handled above it.
 */
public final class ExampleWatermark implements HudElement {

    private static final String TEXT = "Core";

    @Override
    public String getId() {
        return "watermark";
    }

    @Override
    public String getDisplayName() {
        return "Watermark";
    }

    @Override
    public Size getPreferredSize() {
        // Recomputed every frame. Render.textWidth returns 0 with no font
        // installed, so a fixed floor keeps the element grabbable headless.
        return Size.of(Math.max(48f, Render.textWidth(TEXT) + 8f), Render.textHeight() + 6f);
    }

    @Override
    public void render(float x, float y, float w, float h) {
        Render.roundRect(x, y, w, h, 3f, Color.of(0, 0, 0, 120));
        Render.text(TEXT, x + 4f, y + 3f, Color.WHITE);
    }

    @Override
    public HudLayout defaultLayout() {
        return HudLayout.at(Anchor.TOP_LEFT, 4f, 4f);
    }
}
