package dev.px.core.test.example;

import dev.px.core.hud.HudElement;
import dev.px.core.hud.Size;
import lombok.Setter;

/**
 * Throws on demand.
 *
 * <p>Exists to prove the thing you only find out about in production: one badly
 * written HUD element must not be able to take the whole overlay down with it.
 */
public final class ExampleBrokenElement implements HudElement {

    @Setter
    private boolean explodeOnRender;

    @Setter
    private boolean explodeOnMeasure;

    @Override
    public String getId() {
        return "broken";
    }

    @Override
    public Size getPreferredSize() {
        if (explodeOnMeasure) {
            throw new IllegalStateException("deliberate failure while measuring");
        }
        return Size.of(10f, 10f);
    }

    @Override
    public void render(float x, float y, float w, float h) {
        if (explodeOnRender) {
            throw new IllegalStateException("deliberate failure while rendering");
        }
    }
}
