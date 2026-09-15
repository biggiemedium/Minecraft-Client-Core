package dev.px.core.hud;

import dev.px.core.registry.Named;

/**
 * Something drawn on the HUD.
 *
 * <p>Two methods are mandatory: how big you are, and how to draw yourself. That
 * is the whole contract, and it is deliberately the whole contract &mdash; adding
 * an element type must never require understanding the layout engine. Anchoring,
 * scaling, clamping, z-order, hit testing, dragging and persistence are all
 * handled above this interface.
 *
 * <p>Everything here works in <b>natural, unscaled</b> coordinates. The element's
 * scale factor is applied by {@link HudService} as a transform, so an element
 * that never mentions scale is automatically scalable.
 *
 * <p>Core knows nothing about what an element shows. There is no FPS counter, no
 * armour bar and no module list in here; those are the adapter's, built on this.
 *
 * <pre>{@code
 * public final class WatermarkElement extends AbstractHudElement {
 *
 *     public WatermarkElement() {
 *         super("watermark", "Watermark");
 *     }
 *
 *     @Override
 *     public Size getPreferredSize() {
 *         return Size.of(Render.textWidth(label()) + 8f, Render.textHeight() + 6f);
 *     }
 *
 *     @Override
 *     public void render(float x, float y, float w, float h) {
 *         Render.roundRect(x, y, w, h, 3f, Core.themes().getSurface());
 *         Render.text(label(), x + 4f, y + 3f, Color.WHITE);
 *     }
 * }
 * }</pre>
 */
public interface HudElement extends Named {

    /**
     * Stable identity, used as the config key and the registry key.
     *
     * <p>Must not change between builds: renaming it orphans every saved layout
     * for this element. Keep it lowercase and separate from the display label,
     * which is free to change.
     */
    String getId();

    /**
     * Current natural size.
     *
     * <p>Expected to differ every frame. A clock is wider at 12:00 than 1:00, an
     * ArrayList grows as modules are toggled. Nothing caches this, and the anchor
     * formula makes a size change move the element in the right direction on its
     * own.
     */
    Size getPreferredSize();

    /**
     * Draws the element with its top-left corner at {@code (x, y)}.
     *
     * <p>{@code w} and {@code h} are the natural size reported by
     * {@link #getPreferredSize()} this frame, not the scaled size: any scale
     * factor is already applied as a transform around this call.
     */
    void render(float x, float y, float w, float h);

    /**
     * The clickable region, in natural coordinates within the given rectangle.
     *
     * <p>Defaults to the whole rectangle, which is right for almost everything.
     * Override it when the drawn shape is not a rectangle, so that clicking the
     * corner of a circular element correctly misses it and the editor's selection
     * outline traces the real shape.
     */
    default Shape getShape(float x, float y, float w, float h) {
        return Shape.rect(x, y, w, h);
    }

    /**
     * Where this element sits before a user moves it, and what
     * {@code resetLayout} restores.
     */
    default HudLayout defaultLayout() {
        return HudLayout.at(Anchor.TOP_LEFT, 4f, 4f);
    }

    /** Human-readable label for the editor. Free to change; {@link #getId()} is not. */
    default String getDisplayName() {
        return getId();
    }

    /**
     * The registry key is the id.
     *
     * <p>{@link Named} is what lets a {@link dev.px.core.registry.Registry} hold
     * elements and give duplicate detection and lookup for free; the id is the
     * stable name, and {@link #getDisplayName()} is the one shown to users.
     */
    @Override
    default String getName() {
        return getId();
    }
}
