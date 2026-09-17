package dev.px.core.hud;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Size;

import dev.px.core.math.MathUtil;
import dev.px.core.platform.Platform;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The interaction model behind edit mode: what is selected, what is being
 * dragged, where it would land, and what it is snapped to.
 *
 * <p><b>This class draws nothing and listens to nothing.</b> It is the HUD's
 * arithmetic for editing &mdash; hit testing, drag offsets, uniform resize about
 * a fixed corner, edge and centre snapping, handle placement &mdash; exposed as
 * plain state and as a per-frame {@link HudEditorView}. What edit mode
 * <em>looks</em> like, and which key or button does what, belong to the client
 * built on Core, not to Core.
 *
 * <p>A consumer drives it with three calls a frame:
 *
 * <pre>{@code
 * editor.update(mouseX, mouseY);          // apply any in-flight drag or resize
 * HudEditorView view = editor.view();     // geometry to draw
 * hud.drawAll(view.getPlacements());      // draw the elements themselves
 * }</pre>
 *
 * <p>and routes its own input into the action methods:
 *
 * <pre>{@code
 * // in the consumer's screen
 * public void mousePressed(float x, float y, MouseButton button) {
 *     if (button == MouseButton.LEFT) {
 *         editor.press(x, y);
 *     } else if (button == MouseButton.RIGHT && editor.getSelected() != null) {
 *         editor.toggleHiddenSelected();
 *     }
 * }
 * public void mouseReleased() { editor.release(); }
 * public void keyPressed(Key key) {
 *     if (key == Key.L) editor.toggleLockSelected();
 *     if (key == Key.LEFT) editor.nudgeSelected(-1f, 0f);
 * }
 * }</pre>
 *
 * <p>Nothing here reads the cursor or cancels an event on its own. Core has no
 * opinion on whether ALT is the snap-suspend key or whether Escape closes the
 * editor, so {@link #setSnappingSuspended} and {@link #close} are called by
 * whoever owns those bindings.
 *
 * <p>Geometry is refreshed by {@link #update}, and resolved on demand by
 * anything that needs it before the first update. Hit testing therefore never
 * depends on a frame having been drawn &mdash; which it used to, when the
 * overlay renderer was what recorded the placements.
 */
@Getter
public final class HudEditor {

    /** How close to a guide line a dragged edge has to be before it snaps. */
    private static final float SNAP_DISTANCE = 6f;

    private final HudService hud;
    private final Platform platform;

    private boolean active;
    private String selectedId;

    private boolean dragging;
    private Handle resizing;

    /** Cursor position relative to the element's top-left when the drag began. */
    private float grabX;
    private float grabY;

    /** Cursor as last given to {@link #update} or {@link #press}. Core never reads it from anywhere else. */
    private float cursorX;
    private float cursorY;

    /**
     * Whether snapping is currently turned off.
     *
     * <p>Set by the consumer from whatever modifier it uses. Core does not track
     * a key, because which key suspends snapping is a UX decision.
     */
    private boolean snappingSuspended;

    /**
     * Size of a corner resize handle.
     *
     * <p>Settable because the handle rectangle is both what the consumer draws
     * and what {@link #press} hit tests. A consumer that draws a bigger grab
     * target sets it here and the two stay in agreement.
     */
    private float handleSize = 6f;

    /** Gap between the element and its handles, so grabbing one never overlaps the element. */
    private float handleGap = 3f;

    /** Geometry as of the last {@link #update}, so a drag has something to work against. */
    private List<Placement> placements = Collections.emptyList();

    private final List<SnapGuide> guides = new ArrayList<>(2);

    HudEditor(HudService hud, Platform platform) {
        this.hud = hud;
        this.platform = platform;
    }

    // ------------------------------------------------------------- lifecycle

    public void open() {
        if (!active) {
            active = true;
            hud.setEditingFlag(true);
            refresh();
        }
    }

    /** Leaves edit mode and drops all transient state. */
    public void close() {
        if (active) {
            active = false;
            dragging = false;
            resizing = null;
            selectedId = null;
            guides.clear();
            // Placements hold last frame's geometry; keeping them past close would
            // let a stale hit test answer the first click of the next session.
            placements = Collections.emptyList();
            hud.setEditingFlag(false);
        }
    }

    public void toggle() {
        if (active) {
            close();
        } else {
            open();
        }
    }

    public void setSnappingSuspended(boolean suspended) {
        this.snappingSuspended = suspended;
    }

    public void setHandleSize(float size) {
        this.handleSize = Math.max(1f, size);
    }

    public void setHandleGap(float gap) {
        this.handleGap = Math.max(0f, gap);
    }

    // ------------------------------------------------------------- selection

    public void select(String id) {
        this.selectedId = id;
    }

    public void deselect() {
        this.selectedId = null;
        this.dragging = false;
        this.resizing = null;
    }

    /** @return whether the element being dragged is currently snapped to a guide. */
    public boolean isSnapped() {
        return !guides.isEmpty();
    }

    public Placement getSelected() {
        return placementFor(selectedId);
    }

    /** @return the element under the cursor as last reported, or null. */
    public Placement getHovered() {
        return hud.hitTest(currentPlacements(), cursorX, cursorY);
    }

    /**
     * Finds a placement by id, preferring the geometry from the last update.
     *
     * <p>Falls back to a fresh resolve when it is not there. Without that, every
     * operation needing geometry &mdash; nudging, resizing, reading the selection
     * &mdash; would silently do nothing until {@link #update} had been called,
     * which is a trap for a GUI driving the editor from a button rather than the
     * cursor.
     */
    private Placement placementFor(String id) {
        return id == null ? null : findIn(currentPlacements(), id);
    }

    /** @return the cached geometry, resolving once if nothing has been cached yet. */
    private List<Placement> currentPlacements() {
        if (placements.isEmpty()) {
            placements = hud.resolve(true);
        }
        return placements;
    }

    private static Placement findIn(List<Placement> placements, String id) {
        for (Placement placement : placements) {
            if (placement.getId().equals(id)) {
                return placement;
            }
        }
        return null;
    }

    private HudLayout selectedLayout() {
        return selectedId == null ? null : hud.layoutOf(selectedId);
    }

    // ------------------------------------------------- selection-aware actions

    public void toggleLockSelected() {
        HudLayout layout = selectedLayout();
        if (layout != null) {
            layout.toggleLocked();
        }
    }

    /** Hiding keeps the selection: the element stays selectable in the editor to be unhidden. */
    public void toggleHiddenSelected() {
        HudLayout layout = selectedLayout();
        if (layout != null) {
            layout.toggleHidden();
        }
    }

    public void resetSelected() {
        if (selectedId != null) {
            hud.resetLayout(selectedId);
        }
    }

    public void bringSelectedToFront() {
        if (selectedId != null) {
            hud.bringToFront(selectedId);
        }
    }

    public void sendSelectedToBack() {
        if (selectedId != null) {
            hud.sendToBack(selectedId);
        }
    }

    public void setSelectedAnchor(Anchor anchor) {
        if (selectedId != null) {
            hud.setAnchor(selectedId, anchor);
        }
    }

    public void scaleSelected(float delta) {
        HudLayout layout = selectedLayout();
        if (layout != null && !layout.isLocked()) {
            layout.setScale(layout.getScale() + delta);
        }
    }

    /** Moves the selection by whole pixels. Backs arrow-key nudging. */
    public void nudgeSelected(float dx, float dy) {
        Placement placement = getSelected();
        if (placement == null || placement.getLayout().isLocked()) {
            return;
        }
        hud.moveTo(placement, placement.getBounds().getX() + dx, placement.getBounds().getY() + dy);
        refresh();
    }

    // ------------------------------------------------------------------ input

    /**
     * Begins an interaction at a point: grabs a resize handle, selects and starts
     * dragging an element, or clears the selection on empty space.
     *
     * @return whether anything was hit
     */
    public boolean press(float x, float y) {
        if (!active) {
            return false;
        }
        this.cursorX = x;
        this.cursorY = y;

        // A grabbed handle wins over a new selection, otherwise clicking a handle
        // that overlaps another element would select that element instead.
        Placement selected = getSelected();
        if (selected != null && !selected.getLayout().isLocked()) {
            Handle handle = handleAt(selected, x, y);
            if (handle != null) {
                resizing = handle;
                dragging = false;
                return true;
            }
        }

        Placement hit = hud.hitTest(currentPlacements(), x, y);
        if (hit == null) {
            deselect();
            return false;
        }
        selectedId = hit.getId();
        if (hit.getLayout().isLocked()) {
            // Selectable so it can be unlocked, but not draggable.
            dragging = false;
            return true;
        }
        dragging = true;
        grabX = x - hit.getBounds().getX();
        grabY = y - hit.getBounds().getY();
        return true;
    }

    /** Ends any drag or resize. The selection survives. */
    public void release() {
        dragging = false;
        resizing = null;
        guides.clear();
    }

    /**
     * Applies an in-progress drag or resize from the given cursor position, then
     * refreshes the cached geometry.
     *
     * <p>Called once a frame by the consumer, before it reads {@link #view()},
     * so the element is positioned where the cursor is this frame rather than one
     * frame behind. Safe to call when nothing is being dragged: it just refreshes.
     *
     * <p>Core does not read the cursor itself. Passing it in is what lets a drag
     * be driven by a test, a controller or anything else that is not a mouse.
     */
    public void update(float mouseX, float mouseY) {
        if (!active) {
            return;
        }
        this.cursorX = mouseX;
        this.cursorY = mouseY;
        guides.clear();

        Placement placement = getSelected();
        if (placement != null && !placement.getLayout().isLocked()) {
            if (dragging) {
                applyDrag(placement);
            } else if (resizing != null) {
                applyResize(placement);
            }
        }
        refresh();
    }

    /** Re-resolves geometry, so hit testing and the next view see current positions. */
    private void refresh() {
        if (active) {
            placements = hud.resolve(true);
        }
    }

    private void applyDrag(Placement placement) {
        float targetX = cursorX - grabX;
        float targetY = cursorY - grabY;

        Bounds bounds = placement.getBounds();
        if (!snappingSuspended) {
            targetX = snapAxis(targetX, bounds.getWidth(), platform.getScreenWidth(), true);
            targetY = snapAxis(targetY, bounds.getHeight(), platform.getScreenHeight(), false);
        }
        hud.moveTo(placement, targetX, targetY);
    }

    private void applyResize(Placement placement) {
        Bounds bounds = placement.getBounds();
        Size natural = placement.getNatural();
        if (natural.isEmpty()) {
            return;
        }

        // The corner opposite the grabbed handle stays put, which is what makes
        // dragging any of the four corners feel right even though drawing always
        // scales about the top-left.
        float fixedX = resizing.left ? bounds.getRight() : bounds.getX();
        float fixedY = resizing.top ? bounds.getBottom() : bounds.getY();

        float wantedWidth = Math.abs(cursorX - fixedX);
        float wantedHeight = Math.abs(cursorY - fixedY);

        // Uniform: an element sizes itself from its content, so only scale is free.
        float scale = MathUtil.clamp(
                Math.max(wantedWidth / natural.getWidth(), wantedHeight / natural.getHeight()),
                HudLayout.MIN_SCALE, HudLayout.MAX_SCALE);

        HudLayout layout = placement.getLayout();
        layout.setScale(scale);

        float width = natural.getWidth() * layout.getScale();
        float height = natural.getHeight() * layout.getScale();
        hud.moveTo(layout,
                resizing.left ? fixedX - width : fixedX,
                resizing.top ? fixedY - height : fixedY,
                width, height);
    }

    /**
     * Snaps one axis to the near edge, centre or far edge of the screen.
     *
     * @return the adjusted position, recording a guide when it snapped
     */
    private float snapAxis(float position, float size, float screenSize, boolean horizontalAxis) {
        float[] elementLines = { position, position + size / 2f, position + size };
        float[] screenLines = { 0f, screenSize / 2f, screenSize };

        float bestDelta = 0f;
        float bestDistance = SNAP_DISTANCE;
        float bestLine = 0f;
        boolean snapped = false;

        for (float elementLine : elementLines) {
            for (float screenLine : screenLines) {
                float distance = Math.abs(screenLine - elementLine);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestDelta = screenLine - elementLine;
                    bestLine = screenLine;
                    snapped = true;
                }
            }
        }
        if (snapped) {
            // A snap on the horizontal axis constrains x, and so draws as a vertical line.
            guides.add(new SnapGuide(bestLine, horizontalAxis));
            return position + bestDelta;
        }
        return position;
    }

    // ------------------------------------------------------------------- view

    /**
     * @return an immutable snapshot of everything an edit-mode UI needs to draw
     *
     * <p>Cheap enough to call once a frame and never cached, for the same reason
     * nothing else in the HUD is cached: an element's size can change between any
     * two frames.
     */
    public HudEditorView view() {
        if (!active) {
            return new HudEditorView(false, Collections.<Placement>emptyList(), null, null,
                    Collections.<Handle, Bounds>emptyMap(), Collections.<SnapGuide>emptyList(),
                    false, false);
        }
        List<Placement> resolved = currentPlacements();
        Placement selected = findIn(resolved, selectedId);
        Placement hovered = hud.hitTest(resolved, cursorX, cursorY);

        Map<Handle, Bounds> handleBounds = selected == null || selected.getLayout().isLocked()
                ? Collections.<Handle, Bounds>emptyMap()
                : handlesOf(selected);

        return new HudEditorView(true, new ArrayList<>(resolved), selected, hovered,
                handleBounds, new ArrayList<>(guides), dragging, resizing != null);
    }

    /** @return the four corner handle rectangles for a placement, in enum order. */
    public Map<Handle, Bounds> handlesOf(Placement placement) {
        Map<Handle, Bounds> found = new EnumMap<>(Handle.class);
        if (placement == null) {
            return found;
        }
        Bounds bounds = placement.shape().bounds();
        for (Handle handle : Handle.values()) {
            found.put(handle, handleBounds(bounds, handle));
        }
        return found;
    }

    private Handle handleAt(Placement placement, float x, float y) {
        Bounds bounds = placement.shape().bounds();
        for (Handle handle : Handle.values()) {
            if (handleBounds(bounds, handle).contains(x, y)) {
                return handle;
            }
        }
        return null;
    }

    /**
     * Places a corner handle just outside the element, so grabbing one never
     * overlaps the element's own interaction region.
     *
     * <p>Outside is not always possible. A HUD element anchored into a screen
     * corner &mdash; the single most common arrangement there is &mdash; would
     * have that corner's handle sitting off-screen and permanently unclickable.
     * When there is no room outside, the handle flips to the inside edge instead.
     */
    private Bounds handleBounds(Bounds bounds, Handle handle) {
        float outsideX = handle.left ? bounds.getX() - handleGap - handleSize : bounds.getRight() + handleGap;
        float outsideY = handle.top ? bounds.getY() - handleGap - handleSize : bounds.getBottom() + handleGap;

        float x = onScreen(outsideX, platform.getScreenWidth()) ? outsideX
                : handle.left ? bounds.getX() + handleGap : bounds.getRight() - handleGap - handleSize;
        float y = onScreen(outsideY, platform.getScreenHeight()) ? outsideY
                : handle.top ? bounds.getY() + handleGap : bounds.getBottom() - handleGap - handleSize;

        return Bounds.of(x, y, handleSize, handleSize);
    }

    private boolean onScreen(float position, float screenSize) {
        return position >= 0f && position + handleSize <= screenSize;
    }

    /** A corner grab point. Scaling keeps the opposite corner fixed. */
    public enum Handle {

        TOP_LEFT(true, true),
        TOP_RIGHT(false, true),
        BOTTOM_LEFT(true, false),
        BOTTOM_RIGHT(false, false);

        final boolean left;
        final boolean top;

        Handle(boolean left, boolean top) {
            this.left = left;
            this.top = top;
        }

        public boolean isLeft() {
            return left;
        }

        public boolean isTop() {
            return top;
        }
    }
}
