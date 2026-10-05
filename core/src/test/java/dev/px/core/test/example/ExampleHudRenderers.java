package dev.px.core.test.example;

import dev.px.core.layout.Bounds;
import dev.px.core.hud.HudRenderer;
import dev.px.core.hud.HudRendererRegistry;
import dev.px.core.layout.Size;
import dev.px.core.render.Color;
import dev.px.core.render.Render;

/**
 * Taking over an element's look without touching the element.
 *
 * <p>Most elements need nothing here: describing their content is enough, which
 * is what keeps an element to one class. A {@link HudRenderer} is the other
 * path, for when a client wants an element to look different from how its author
 * drew it &mdash; including elements it did not write and cannot change.
 *
 * <p><b>A renderer replaces the look and nothing else.</b> The element still
 * describes its content, and that description is still what Core measures, so
 * size, anchoring, clamping and hit testing are unaffected by whatever the
 * renderer chooses to draw. The suite asserts exactly that.
 */
public final class ExampleHudRenderers {

    /**
     * How many elements have been drawn since the last reset.
     *
     * <p>A renderer is the only thing in the suite that can observe a draw, which
     * makes this the proof that Core itself never draws: post a render event and
     * this must not move.
     */
    private static int draws;

    private ExampleHudRenderers() {
    }

    public static int getDraws() {
        return draws;
    }

    public static void resetDraws() {
        draws = 0;
    }

    /** Overrides the dial's own content with a flat square, to prove a renderer wins. */
    public static void installInto(HudRendererRegistry renderers) {
        renderers.register(HudRenderer.of(ExampleDial.class, (dial, placement) -> {
            draws++;
            Bounds at = placement.getBounds();
            Size size = placement.getNatural();
            Render.rect(at.getX(), at.getY(), size.getWidth(), size.getHeight(),
                    Color.of(255, 0, 255, 90));
        }));
    }
}
