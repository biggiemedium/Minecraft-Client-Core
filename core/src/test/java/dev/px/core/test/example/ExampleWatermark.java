package dev.px.core.test.example;

import dev.px.core.hud.Anchor;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudElement;
import dev.px.core.hud.HudLayout;
import dev.px.core.render.Color;

/**
 * The floor: a whole HUD element in six lines.
 *
 * <p>An id and a description of what it is made of. Its size comes from that
 * description, so there is no second method to keep in step. Anchoring, scaling,
 * clamping, z-order, hit testing, dragging and persistence are all above it.
 */
public final class ExampleWatermark implements HudElement {

    public static final String TEXT = "Core";

    @Override
    public String getId() {
        return "watermark";
    }

    @Override
    public String getDisplayName() {
        return "Watermark";
    }

    @Override
    public void content(Content c) {
        c.background(Color.of(0, 0, 0, 120), 3f).padding(4f, 3f);
        // A floor, so the element stays grabbable in the editor even with no font
        // installed -- which is exactly the situation the suite runs in.
        c.min(48f, 12f);
        c.text(TEXT, Color.WHITE);
    }

    @Override
    public HudLayout defaultLayout() {
        return HudLayout.at(Anchor.TOP_LEFT, 4f, 4f);
    }
}
