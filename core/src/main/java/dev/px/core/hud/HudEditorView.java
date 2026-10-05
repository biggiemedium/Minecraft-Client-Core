package dev.px.core.hud;

import dev.px.core.layout.Bounds;

import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to draw one frame of an edit-mode UI, and nothing that
 * decides how it looks.
 *
 * <p>An immutable snapshot taken from {@link HudEditor#view()}. Core computes
 * the geometry &mdash; which element is selected, which is under the cursor,
 * where the resize handles are, which alignment guides are live &mdash; and
 * stops there. Colours, outlines, backdrops, labels and handle graphics are the
 * consumer's, because they are the look of someone's client rather than a
 * property of the layout.
 *
 * <pre>{@code
 * // the consumer's editor screen, once per frame
 * editor.update(mouseX, mouseY);
 * HudEditorView view = editor.view();
 *
 * Render.rect(0f, 0f, screenW, screenH, myBackdrop);   // your backdrop
 * hud.drawAll(view.getPlacements());                   // the elements
 *
 * for (Placement placement : view.getPlacements()) {
 *     if (view.isSelected(placement)) {
 *         placement.shape().stroke(1.5f, myAccent);    // your outline
 *     } else if (view.isHovered(placement)) {
 *         placement.shape().stroke(1f, myHover);
 *     }
 * }
 * for (Bounds handle : view.getHandles().values()) {
 *     Render.rect(handle.getX(), handle.getY(), handle.getWidth(), handle.getHeight(), myAccent);
 * }
 * }</pre>
 *
 * <p>Note that a {@link Shape} still knows how to {@link Shape#stroke} itself.
 * Core never calls it, but it is what lets a consumer outline a circular or
 * polygonal element correctly without a switch over shape types.
 */
@Getter
public final class HudEditorView {

    /** Whether edit mode is open at all. A closed editor yields an empty view. */
    private final boolean active;

    /**
     * Every element resolved this frame, in ascending z-order.
     *
     * <p>Includes hidden elements while editing, so they can be shown somehow
     * &mdash; faintly, outlined, however the consumer likes &mdash; and selected
     * to be brought back. {@code placement.getLayout().isHidden()} says which.
     */
    private final List<Placement> placements;

    /** The selected element, or null. */
    private final Placement selected;

    /** The element under the cursor, or null. Null while dragging is not implied. */
    private final Placement hovered;

    /**
     * Where the four corner resize handles are.
     *
     * <p>Empty when nothing is selected or the selection is locked, which is
     * exactly when no handle is grabbable. Sized by
     * {@link HudEditor#getHandleSize()}, and flipped inside the element when
     * there is no room outside it, so the rectangles here are always the ones
     * {@link HudEditor#press} will actually hit.
     */
    private final Map<HudEditor.Handle, Bounds> handles;

    /** Alignment lines the dragged element is currently snapped to. Usually empty. */
    private final List<SnapGuide> guides;

    private final boolean dragging;

    private final boolean resizing;

    HudEditorView(boolean active, List<Placement> placements, Placement selected, Placement hovered,
                  Map<HudEditor.Handle, Bounds> handles, List<SnapGuide> guides,
                  boolean dragging, boolean resizing) {
        this.active = active;
        this.placements = Collections.unmodifiableList(placements);
        this.selected = selected;
        this.hovered = hovered;
        this.handles = Collections.unmodifiableMap(handles);
        this.guides = Collections.unmodifiableList(guides);
        this.dragging = dragging;
        this.resizing = resizing;
    }

    /** @return whether this placement is the selected one. Identity-safe across frames, by id. */
    public boolean isSelected(Placement placement) {
        return placement != null && selected != null && selected.getId().equals(placement.getId());
    }

    public boolean isHovered(Placement placement) {
        return placement != null && hovered != null && hovered.getId().equals(placement.getId());
    }

    /** @return whether an element is being dragged or resized right now. */
    public boolean isInteracting() {
        return dragging || resizing;
    }
}
