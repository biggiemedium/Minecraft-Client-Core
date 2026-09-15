package dev.px.core.hud;

import dev.px.core.event.Priority;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.MouseEvent;
import dev.px.core.event.impl.ScrollEvent;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.math.MathUtil;
import dev.px.core.platform.Platform;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Edit mode: select, drag, scale, lock, hide and reorder HUD elements.
 *
 * <p>Input arrives through Core's ordinary {@link MouseEvent}, {@link KeyEvent}
 * and {@link ScrollEvent} at {@link Priority#HIGHEST}, and every one of them is
 * cancelled while the editor is open. That cancellation <em>is</em> the
 * "swallows input" requirement: with the events consumed before anything else
 * sees them, no module toggles and no gameplay action can fire behind the editor.
 *
 * <p>Selection outlines are drawn by asking the element's own {@link Shape} to
 * {@link Shape#stroke}. Nothing here switches on shape type, so a circular or
 * polygonal element gets a correct outline and correct hit testing on the day it
 * is written.
 *
 * <p>Dragging follows the mouse from {@link Platform#getMouseX()} each frame
 * rather than from a move event, because Core has no mouse-move event and does
 * not need one: the drag is only interesting while something is being drawn.
 */
@Getter
public final class HudEditor {

    private static final float SNAP_DISTANCE = 6f;
    private static final float HANDLE_SIZE = 6f;

    /** Handles sit outside the element so clicking one never overlaps its own shape. */
    private static final float HANDLE_GAP = 3f;

    private static final Color BACKDROP = Color.of(0, 0, 0, 110);
    private static final Color HOVER = Color.of(255, 255, 255, 90);
    private static final Color LOCKED = Color.of(255, 150, 60, 200);
    private static final Color GUIDE = Color.of(120, 220, 255, 180);
    private static final Color LABEL = Color.of(235, 235, 240, 220);

    private final HudService hud;
    private final Platform platform;

    /** Accent used for the selection outline. Pointed at the active theme by Core at startup. */
    @Setter
    private Supplier<Color> accent = () -> Color.of(120, 200, 255);

    private boolean active;
    private String selectedId;

    private boolean dragging;
    private Handle resizing;

    /** Cursor position relative to the element's top-left when the drag began. */
    private float grabX;
    private float grabY;

    /** Held modifiers, tracked across events so a key pressed mid-drag is noticed. */
    private boolean suspendSnapping;

    /** Last frame's geometry, so drag maths has something to work against before the next resolve. */
    private List<Placement> lastPlacements = Collections.emptyList();

    private final List<Guide> guides = new ArrayList<>(4);

    HudEditor(HudService hud, Platform platform) {
        this.hud = hud;
        this.platform = platform;
    }

    // ------------------------------------------------------------- lifecycle

    public void open() {
        if (!active) {
            active = true;
            hud.setEditingFlag(true);
        }
    }

    /**
     * Leaves edit mode and drops all transient state.
     *
     * <p>Note that while the editor is open it cancels <em>every</em> key, which
     * includes whatever bind opened it. {@code ESCAPE} is therefore the way out:
     * it clears the selection if there is one, and closes the editor otherwise.
     */
    public void close() {
        if (active) {
            active = false;
            dragging = false;
            resizing = null;
            selectedId = null;
            guides.clear();
            // Placements hold last frame's geometry; keeping them past close would
            // let a stale hit test answer the first click of the next session.
            lastPlacements = Collections.emptyList();
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

    /**
     * Finds a placement by id, preferring last frame's geometry.
     *
     * <p>Falls back to a fresh resolve when it is not there. Without that, every
     * operation needing geometry &mdash; nudging, resizing, reading the selection
     * &mdash; would silently do nothing until a frame had been drawn, which is a
     * trap for a GUI driving the editor from a button rather than the mouse.
     */
    private Placement placementFor(String id) {
        if (id == null) {
            return null;
        }
        Placement cached = findIn(lastPlacements, id);
        return cached != null ? cached : findIn(hud.resolve(true), id);
    }

    private static Placement findIn(List<Placement> placements, String id) {
        for (Placement placement : placements) {
            if (placement.getId().equals(id)) {
                return placement;
            }
        }
        return null;
    }

    /**
     * Keeps the snap-suspend flag in step with the ALT key.
     *
     * <p>Reading it from the event's modifier set alone is not enough: adapters
     * disagree on whether a modifier key's own press event lists itself, so ALT
     * could get stuck on after release. The key itself is authoritative when it
     * is the one that moved.
     */
    private void trackSnapModifier(KeyEvent event) {
        if (Modifier.of(event.getKey()) == Modifier.ALT) {
            suspendSnapping = event.isPressed();
        } else {
            suspendSnapping = event.getModifiers().contains(Modifier.ALT);
        }
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

    /** Hiding keeps the selection: the element stays visible in the editor to be unhidden. */
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
    }

    // ------------------------------------------------------------------ input

    @Subscribe(priority = Priority.HIGHEST, ignoreListening = true)
    private void onMouse(MouseEvent event) {
        if (!active) {
            return;
        }
        suspendSnapping = event.getModifiers().contains(Modifier.ALT);

        if (event.isPressed()) {
            if (event.getButton() == MouseButton.LEFT) {
                pressLeft(event.getX(), event.getY());
            } else if (event.getButton() == MouseButton.RIGHT) {
                // Right-click toggles visibility, the quickest way to switch an element off.
                Placement hit = hud.hitTest(lastPlacements, event.getX(), event.getY());
                if (hit != null && !hit.getLayout().isLocked()) {
                    hit.getLayout().toggleHidden();
                }
            }
        } else {
            dragging = false;
            resizing = null;
            guides.clear();
        }
        event.cancel();
    }

    private void pressLeft(float mouseX, float mouseY) {
        // A grabbed handle wins over a new selection, otherwise clicking a handle
        // that overlaps another element would select that element instead.
        Placement selected = getSelected();
        if (selected != null && !selected.getLayout().isLocked()) {
            Handle handle = handleAt(selected, mouseX, mouseY);
            if (handle != null) {
                resizing = handle;
                dragging = false;
                return;
            }
        }

        Placement hit = hud.hitTest(lastPlacements, mouseX, mouseY);
        if (hit == null) {
            deselect();
            return;
        }
        selectedId = hit.getId();
        if (hit.getLayout().isLocked()) {
            // Selectable so it can be unlocked, but not draggable.
            dragging = false;
            return;
        }
        dragging = true;
        grabX = mouseX - hit.getBounds().getX();
        grabY = mouseY - hit.getBounds().getY();
    }

    @Subscribe(priority = Priority.HIGHEST, ignoreListening = true)
    private void onScroll(ScrollEvent event) {
        if (!active) {
            return;
        }
        scaleSelected(event.getAmount() > 0f ? 0.05f : -0.05f);
        event.cancel();
    }

    @Subscribe(priority = Priority.HIGHEST, ignoreListening = true)
    private void onKey(KeyEvent event) {
        if (!active) {
            return;
        }
        trackSnapModifier(event);
        if (!event.isPressed()) {
            event.cancel();
            return;
        }

        boolean fine = event.getModifiers().contains(Modifier.SHIFT);
        float step = fine ? 10f : 1f;

        switch (event.getKey()) {
            case ESCAPE:
                // Escape backs out one level: clear the selection, or leave the editor.
                if (selectedId != null) {
                    deselect();
                } else {
                    close();
                }
                break;
            case LEFT: nudgeSelected(-step, 0f); break;
            case RIGHT: nudgeSelected(step, 0f); break;
            case UP: nudgeSelected(0f, -step); break;
            case DOWN: nudgeSelected(0f, step); break;
            case L: toggleLockSelected(); break;
            case H: toggleHiddenSelected(); break;
            case R: resetSelected(); break;
            case PAGE_UP: bringSelectedToFront(); break;
            case PAGE_DOWN: sendSelectedToBack(); break;
            default: break;
        }
        event.cancel();
    }

    // ----------------------------------------------------------------- update

    /**
     * Applies an in-progress drag or resize from the current cursor position.
     *
     * <p>Called by {@link HudService#renderFrame()} before geometry is resolved,
     * so the element is drawn at the position the mouse is at this frame rather
     * than one frame behind.
     */
    void beforeResolve() {
        guides.clear();
        Placement placement = getSelected();
        if (placement == null || placement.getLayout().isLocked()) {
            return;
        }
        if (dragging) {
            applyDrag(placement);
        } else if (resizing != null) {
            applyResize(placement);
        }
    }

    private void applyDrag(Placement placement) {
        float targetX = platform.getMouseX() - grabX;
        float targetY = platform.getMouseY() - grabY;

        Bounds bounds = placement.getBounds();
        if (!suspendSnapping) {
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
        // dragging any of the four corners feel right even though rendering always
        // scales about the top-left.
        float fixedX = resizing.left ? bounds.getRight() : bounds.getX();
        float fixedY = resizing.top ? bounds.getBottom() : bounds.getY();

        float wantedWidth = Math.abs(platform.getMouseX() - fixedX);
        float wantedHeight = Math.abs(platform.getMouseY() - fixedY);

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
    private float snapAxis(float position, float size, float screenSize, boolean horizontal) {
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
            guides.add(new Guide(bestLine, horizontal));
            return position + bestDelta;
        }
        return position;
    }

    // -------------------------------------------------------------- rendering

    /** Dims the game behind the HUD so the editor reads as a separate mode. */
    void renderBackdrop() {
        Render.rect(0f, 0f, platform.getScreenWidth(), platform.getScreenHeight(), BACKDROP);
    }

    /** Draws outlines, handles and guides on top of the elements. */
    void renderOverlay(List<Placement> placements) {
        this.lastPlacements = placements;

        float mouseX = platform.getMouseX();
        float mouseY = platform.getMouseY();
        Placement hovered = hud.hitTest(placements, mouseX, mouseY);

        for (Placement placement : placements) {
            boolean isSelected = placement.getId().equals(selectedId);
            if (!isSelected && placement != hovered) {
                continue;
            }
            Color outline = placement.getLayout().isLocked() ? LOCKED
                    : isSelected ? accent.get() : HOVER;
            placement.shape().stroke(isSelected ? 1.5f : 1f, outline);
        }

        Placement selected = placementFor(selectedId);
        if (selected != null) {
            if (!selected.getLayout().isLocked()) {
                renderHandles(selected);
            }
            renderLabel(selected);
        }

        for (Guide guide : guides) {
            if (guide.horizontal) {
                Render.line(guide.position, 0f, guide.position, platform.getScreenHeight(), 1f, GUIDE);
            } else {
                Render.line(0f, guide.position, platform.getScreenWidth(), guide.position, 1f, GUIDE);
            }
        }
    }

    private void renderHandles(Placement placement) {
        Bounds bounds = placement.shape().bounds();
        for (Handle handle : Handle.values()) {
            Bounds box = handleBounds(bounds, handle);
            Render.rect(box.getX(), box.getY(), box.getWidth(), box.getHeight(), accent.get());
        }
    }

    private void renderLabel(Placement placement) {
        Bounds bounds = placement.shape().bounds();
        HudLayout layout = placement.getLayout();
        String text = placement.getElement().getDisplayName()
                + "  " + layout.getAnchor().name().toLowerCase()
                + "  x" + String.format("%.2f", layout.getScale())
                + (layout.isLocked() ? "  locked" : "")
                + (layout.isHidden() ? "  hidden" : "");
        // Below the element, unless that would fall off the bottom of the screen.
        float y = bounds.getBottom() + HANDLE_GAP + HANDLE_SIZE;
        if (y + 10f > platform.getScreenHeight()) {
            y = bounds.getY() - HANDLE_GAP - HANDLE_SIZE - 10f;
        }
        Render.text(text, bounds.getX(), y, LABEL);
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
        float outsideX = handle.left ? bounds.getX() - HANDLE_GAP - HANDLE_SIZE : bounds.getRight() + HANDLE_GAP;
        float outsideY = handle.top ? bounds.getY() - HANDLE_GAP - HANDLE_SIZE : bounds.getBottom() + HANDLE_GAP;

        float x = onScreen(outsideX, platform.getScreenWidth()) ? outsideX
                : handle.left ? bounds.getX() + HANDLE_GAP : bounds.getRight() - HANDLE_GAP - HANDLE_SIZE;
        float y = onScreen(outsideY, platform.getScreenHeight()) ? outsideY
                : handle.top ? bounds.getY() + HANDLE_GAP : bounds.getBottom() - HANDLE_GAP - HANDLE_SIZE;

        return Bounds.of(x, y, HANDLE_SIZE, HANDLE_SIZE);
    }

    private static boolean onScreen(float position, float screenSize) {
        return position >= 0f && position + HANDLE_SIZE <= screenSize;
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
    }

    /** An alignment line drawn while an element is snapped to it. */
    private static final class Guide {

        final float position;
        final boolean horizontal;

        Guide(float position, boolean horizontal) {
            this.position = position;
            this.horizontal = horizontal;
        }
    }
}
