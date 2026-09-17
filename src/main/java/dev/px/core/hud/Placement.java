package dev.px.core.hud;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.layout.Shape;
import dev.px.core.layout.Size;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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
     * The element's content as it described itself this frame, already measured.
     *
     * <p>This is what makes the description happen once. The same box that was
     * measured to produce {@link #natural} is the box that gets drawn, so a
     * padding change moves the outline and the text together by construction
     * rather than by the author remembering to change two methods.
     */
    private final Content content;

    /**
     * The element's interaction region in screen space.
     *
     * <p>Built at natural size and then scaled about the element's top-left, which
     * is why an element never has to account for its own scale when overriding
     * {@link HudElement#getShape}.
     */
    public Shape shape() {
        float width = this.natural.getWidth();
        float height = this.natural.getHeight();

        Shape declared = element.getShape(bounds.getX(), bounds.getY(), width, height);
        Shape region = declared != null ? declared : derived(width, height);

        float scale = layout.getScale();
        return scale == 1f ? region : region.scaled(bounds.getX(), bounds.getY(), scale);
    }

    /**
     * Works the interaction region out from what the element described.
     *
     * <p>Every box the element gave a background contributes its own outline, at
     * its own corner radius, so a rounded panel is a rounded region and a list of
     * rows is those rows. An element that fills nothing &mdash; plain text, or a
     * {@link Content#custom} callback Core cannot see inside &mdash; falls back
     * to its whole rectangle, which is the safe answer and the one an element can
     * always replace by overriding {@link HudElement#getShape}.
     */
    /**
     * @return the separate regions this element visibly fills, in draw order
     *
     * <p>One entry for a plain panel, one per row for a list, empty for an
     * element that fills nothing it described. Exposed because an editor may want
     * to treat the parts individually &mdash; highlighting the row under the
     * cursor rather than the whole element &mdash; which the combined
     * {@link #shape()} cannot express.
     */
    /**
     * @return where a named part of this element's content ended up, or null
     *
     * <p>The same lookup a GUI component uses to map a click onto what it drew.
     * An element whose content names a part can use it for the same purpose.
     */
    public Bounds silhouetteOf(String name) {
        if (content == null) {
            return null;
        }
        content.layout(bounds.getX(), bounds.getY(), natural.getWidth(), natural.getHeight());
        return content.find(name);
    }

    public List<Shape> silhouette() {
        List<Shape> filled = new ArrayList<>(4);
        if (content == null) {
            return filled;
        }
        content.layout(bounds.getX(), bounds.getY(),
                natural.getWidth(), natural.getHeight());
        content.silhouette(filled);

        float scale = layout.getScale();
        if (scale != 1f) {
            for (int i = 0; i < filled.size(); i++) {
                filled.set(i, filled.get(i).scaled(bounds.getX(), bounds.getY(), scale));
            }
        }
        return filled;
    }

    private Shape derived(float width, float height) {
        if (content == null) {
            return Shape.rect(bounds.getX(), bounds.getY(), width, height);
        }
        content.layout(bounds.getX(), bounds.getY(), width, height);

        List<Shape> filled = new ArrayList<>(4);
        content.silhouette(filled);
        if (filled.isEmpty()) {
            return Shape.rect(bounds.getX(), bounds.getY(), width, height);
        }
        return Shape.union(filled);
    }

    public boolean hits(float x, float y) {
        return shape().contains(x, y);
    }

    public String getId() {
        return element.getId();
    }
}
