package dev.px.core.hud;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * An element resolved for one frame: where it ended up and how big it is.
 *
 * <p>Produced by {@link HudService#resolve()}, consumed by rendering, hit testing
 * and the editor. It exists so those three agree on geometry by construction
 * rather than by each recomputing it and hoping they match.
 *
 * <p>The distinction between {@link #getNatural()} and {@link #getBounds()} is
 * the scale factor. An element draws at natural size inside a scale transform;
 * the bounds are what it actually occupies on screen and are what anchoring,
 * clamping and hit testing use.
 */
@Getter
@RequiredArgsConstructor
public final class Placement {

    private final HudElement element;
    private final HudLayout layout;

    /** Unscaled size the element reported this frame. */
    private final Size natural;

    /** Scaled, clamped, screen-space rectangle the element occupies. */
    private final Bounds bounds;

    /**
     * The element's interaction region in screen space.
     *
     * <p>Built at natural size and then scaled about the element's top-left, which
     * is why an element never has to account for its own scale when overriding
     * {@link HudElement#getShape}.
     */
    public Shape shape() {
        Shape natural = element.getShape(bounds.getX(), bounds.getY(),
                this.natural.getWidth(), this.natural.getHeight());
        float scale = layout.getScale();
        return scale == 1f ? natural : natural.scaled(bounds.getX(), bounds.getY(), scale);
    }

    public boolean hits(float x, float y) {
        return shape().contains(x, y);
    }

    public String getId() {
        return element.getId();
    }
}
