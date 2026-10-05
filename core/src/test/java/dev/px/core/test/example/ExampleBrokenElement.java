package dev.px.core.test.example;

import dev.px.core.layout.Content;
import dev.px.core.hud.HudElement;
import lombok.Getter;
import lombok.Setter;

/**
 * Throws on demand.
 *
 * <p>Exists to prove the thing you only find out about in production: one badly
 * written HUD element must not be able to take the whole overlay down with it.
 *
 * <p>The two failure modes now fall naturally out of when things happen.
 * Describing content is Core's call during {@code resolve}, so
 * {@code explodeOnMeasure} throws from {@link #content}. Drawing happens later,
 * inside {@code drawAll}, so {@code explodeOnRender} throws from the drawing
 * callback. Both have to be contained, and they are contained in different
 * places.
 */
@Getter
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
    public void content(Content c) {
        if (explodeOnMeasure) {
            throw new IllegalStateException("deliberate failure while measuring");
        }
        c.custom(10f, 10f, (x, y, w, h) -> {
            if (explodeOnRender) {
                throw new IllegalStateException("deliberate failure while rendering");
            }
        });
    }
}
