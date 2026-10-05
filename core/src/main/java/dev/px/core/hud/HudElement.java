package dev.px.core.hud;

import dev.px.core.layout.Content;
import dev.px.core.layout.Shape;

import dev.px.core.registry.Named;

/**
 * Something the HUD positions.
 *
 * <p>Two methods: who you are, and what you are made of. That is the whole
 * contract, and it is deliberately the whole contract &mdash; adding an element
 * type must never require understanding the layout engine. Anchoring, scaling,
 * clamping, z-order, hit testing, dragging and persistence are all handled above
 * this interface.
 *
 * <p><b>You describe your content once and get both size and appearance from
 * it.</b> That is the point: the old arrangement had you measure yourself in one
 * method and draw yourself in another, and the two drifted apart the first time
 * anyone changed a padding. {@link #content} replaces both.
 *
 * <p>Core still owns the geometry and you still own the look. A client that
 * wants to restyle an element it did not write registers a {@link HudRenderer}
 * for that type, which replaces the element's own content entirely.
 *
 * <p>Everything here works in <b>natural, unscaled</b> coordinates. The
 * element's scale factor is applied by {@link HudService#drawAll} as a transform
 * around the whole element, so an element that never mentions scale is
 * automatically scalable.
 *
 * <p>Core knows nothing about what an element shows. There is no FPS counter, no
 * armour bar and no module list in here; those are the client's, built on this.
 *
 * <pre>{@code
 * public final class WatermarkElement implements HudElement {
 *
 *     @Override public String getId() { return "watermark"; }
 *
 *     @Override public void content(Content c) {
 *         c.background(Color.of(0, 0, 0, 120), 3f).padding(4f, 3f);
 *         c.text("Core", Color.WHITE);
 *     }
 * }
 *
 * Core.hud().register(new WatermarkElement());   // that is the whole element
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
     * Describes what this element is made of, once per frame.
     *
     * <p>The only method you have to write. Core measures the box you describe
     * to get the element's size, anchors and clamps it, and then draws that same
     * box &mdash; so size and appearance come from one description and cannot
     * disagree. See {@link Content} for the parts available, and
     * {@link Content#custom} for drawing something it cannot express.
     *
     * <p>Called every frame, and expected to describe something different each
     * time: a clock is wider at 12:00 than 1:00, an ArrayList grows as modules
     * are toggled. Nothing is cached between frames, and the anchor formula makes
     * a size change move the element in the right direction on its own.
     *
     * <p>This is also where an element should consult
     * {@link AbstractHudElement#isEditing()} if it wants to show a stable sample
     * while the user is positioning it.
     *
     * @param content the element's root box, a column
     */
    void content(Content content);

    /**
     * The clickable region, in natural coordinates within the given rectangle.
     *
     * <p><b>Returns null by default, which means "work it out from my
     * content"</b> &mdash; and that is usually the right answer. A box with a
     * rounded background gets a rounded region; an element made of several
     * panels, such as a list of per-module rows, gets those panels rather than
     * the rectangle around them, so the empty space beside a short row is
     * correctly not part of the element. You declared that geometry when you
     * described your content, and declaring it again here is how the two drift
     * apart.
     *
     * <p>Override it only when the region genuinely is not what the content
     * describes &mdash; most often when the content is a
     * {@link Content#custom} callback, which Core cannot see inside:
     *
     * <pre>{@code
     * // A dial drawn by a custom callback: only the disc is clickable.
     * @Override public Shape getShape(float x, float y, float w, float h) {
     *     return Shape.circle(x + w / 2f, y + h / 2f, w / 2f);
     * }
     * }</pre>
     *
     * @return the region, or null to derive it from {@link #content}
     */
    default Shape getShape(float x, float y, float w, float h) {
        return null;
    }

    /**
     * Where this element sits before a user moves it, and what
     * {@code resetLayout} restores.
     */
    default HudLayout defaultLayout() {
        return HudLayout.at(Anchor.TOP_LEFT, 4f, 4f);
    }

    /** Human-readable label for an editor UI. Free to change; {@link #getId()} is not. */
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
