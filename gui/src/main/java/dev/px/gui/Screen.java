package dev.px.gui;

import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Size;
import dev.px.core.util.Validate;
import dev.px.gui.render.WidgetRendererRegistry;
import dev.px.gui.widget.Tooltip;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Shows a tree of widgets, with popups and a tooltip above it: lays them out,
 * works out what is hovered, and draws them through the renderers it was given.
 *
 * <pre>{@code
 * Column menu = new Column().gap(4f);
 * menu.add(new Label("My Client"));
 * menu.add(new Button("Singleplayer").onPress(adapter::singleplayer));
 * Screen title = new Screen(look, menu);
 *
 * // in the game screen hosting it
 * title.resize(width, height);        // on open, and whenever the window resizes
 * title.update(mouseX, mouseY);       // every frame: describe, lay out, hover
 * title.draw();                       // every frame: paint what update laid out
 * }</pre>
 *
 * <p>A screen is built, not subclassed: the client composes the tree, and the
 * behaviour lives in its widgets and their listeners. The root fills the screen.
 *
 * <p><b>Layers, drawn in order:</b> the root, then each {@linkplain #popup popup}
 * in the order it was opened, then the ghost of anything being
 * {@linkplain Widget#draggable dragged}, then the {@linkplain Tooltip tooltip}.
 * A popup is never clipped by what opened it, and is hit before anything beneath
 * it; the ghost and the tooltip are never hit at all.
 *
 * <p><b>Input.</b> The host passes the game's input in through {@link #press},
 * {@link #release}, {@link #scroll}, {@link #key} and {@link #typed}, in Core's
 * own {@code Key} and {@code MouseButton}. A press goes to the widget under the
 * cursor and up through its containers until one takes it; keys go to the
 * focused widget and up the same way. Which key means what beyond that is the
 * client's: bind them to {@link #focusNext}, {@link #activate} and
 * {@link #cancel}. A widget dragging something has the mouse captured and is
 * told where the cursor is every {@link #update}.
 *
 * <pre>{@code
 * if (!screen.key(key, modifiers)) {
 *     if (key == Key.TAB) screen.focusNext();
 *     else if (key == Key.ENTER) screen.activate();
 *     else if (key == Key.ESCAPE && !screen.cancel()) closeTheGameScreen();
 * }
 * }</pre>
 *
 * <p><b>It subscribes to nothing and never draws on its own.</b> The game screen
 * hosting it calls in, as it does with the HUD editor. {@link #update} does all
 * the measuring, so anything that asks where a widget is between frames &mdash;
 * hit testing, a click &mdash; reads the last update's geometry and never
 * depends on something having been drawn. {@link #draw} only paints.
 *
 * <p>Game thread only.
 */
public final class Screen {

    /** The look: a renderer per widget type. */
    @Getter
    private final WidgetRendererRegistry renderers;

    @Getter
    private final Widget root;

    @Getter
    private float width;

    @Getter
    private float height;

    private LongSupplier clock = System::currentTimeMillis;

    /** Counts updates, so each widget is described once an update however often it is measured. */
    private long frame;

    private boolean laidOut;

    /** The deepest widget under the cursor at the last update, or null. */
    @Getter
    private Widget hovered;

    /** {@link #hovered} and every container around it, so the flags can be cleared again. */
    private final List<Widget> hoverChain = new ArrayList<>(8);

    // ---- popups

    // ---- input

    /** Receives keys and characters. */
    @Getter
    private Widget focused;

    /** Has the mouse until the button comes up, and is told where the cursor goes. */
    @Getter
    private Widget captured;

    // ---- drag and drop

    /** How far the cursor moves from a press before a drag starts. */
    @Getter
    private float dragThreshold = 4f;

    /** A draggable widget pressed, and where, until the cursor moves far enough or the button comes up. */
    private Widget pressedDraggable;
    private float pressX;
    private float pressY;

    /** The widget being dragged, what it carries, and where the cursor held it from its corner. */
    @Getter
    private Widget dragSource;
    @Getter
    private Object dragPayload;
    private float grabX;
    private float grabY;

    /** The widget a drop would land on now, or null. */
    @Getter
    private Widget dropTarget;

    /** What is drawn under the cursor while dragging, or null. */
    @Getter
    private Widget dragGhost;

    private String memoryClipboard = "";
    private Supplier<String> clipboardRead = () -> memoryClipboard;
    private Consumer<String> clipboardWrite = text -> memoryClipboard = text;

    private final List<Widget> popups = new ArrayList<>(2);
    private final List<Popup> placements = new ArrayList<>(2);

    // ---- the tooltip

    /** How long the cursor rests before a tooltip shows, in the clock's milliseconds. */
    @Getter
    private long tooltipDelay = 500L;

    /** How far from the cursor a tooltip sits, before flipping to stay on screen. */
    @Getter
    private float tooltipOffsetX = 12f;

    @Getter
    private float tooltipOffsetY = 12f;

    /** The widest a tooltip is laid out; a renderer's {@code paragraph} wraps to it. Unlimited by default. */
    @Getter
    private float tooltipMaxWidth = Float.POSITIVE_INFINITY;

    private final Tooltip tooltip = new Tooltip();

    /**
     * The same tooltip, as a {@link Widget}: the engine's methods are reached
     * through this package's type, not the widget package's.
     */
    private final Widget tooltipNode = tooltip;

    /** The widget whose tooltip the cursor is resting on, and since when. */
    private Widget tooltipSource;
    private long restingSince;
    private boolean tooltipShown;

    /**
     * @throws IllegalArgumentException if {@code root} is already inside a
     *         container or the root of another screen or popup
     */
    public Screen(WidgetRendererRegistry renderers, Widget root) {
        this.renderers = Validate.notNull(renderers, "renderers");
        this.root = Validate.notNull(root, "root");
        Validate.check(root.parent == null, "a screen's root cannot be inside a container");
        Validate.check(root.screen == null, "a widget can be the root of only one screen or popup");
        root.screen = this;
        tooltipNode.screen = this;
    }

    /**
     * Replaces the clock, in milliseconds, that the screen's time is read from.
     *
     * <p>System time by default. A test hands in its own so it can control time.
     */
    public Screen clock(LongSupplier source) {
        this.clock = Validate.notNull(source, "source");
        return this;
    }

    /** @return the screen's time in milliseconds, from its clock. */
    public long now() {
        return clock.getAsLong();
    }

    /**
     * Sets how long the cursor has to rest on a widget before its tooltip shows.
     *
     * <p>A tuning knob, half a second by default; zero shows it at once.
     */
    public Screen tooltipDelay(long millis) {
        Validate.check(millis >= 0L, "a tooltip delay cannot be negative");
        this.tooltipDelay = millis;
        return this;
    }

    /**
     * Caps how wide a tooltip is laid out, so a renderer that describes it with
     * {@code c.paragraph(...)} wraps a long one onto several lines.
     */
    public Screen tooltipMaxWidth(float width) {
        Validate.check(width > 0f, "a tooltip's maximum width must be positive");
        this.tooltipMaxWidth = width;
        return this;
    }

    /** Sets how far right of and below the cursor a tooltip sits. Twelve each by default. */
    public Screen tooltipOffset(float x, float y) {
        this.tooltipOffsetX = x;
        this.tooltipOffsetY = y;
        return this;
    }

    // ---------------------------------------------------------------- frame

    /**
     * Sets the size the root is laid out at.
     *
     * <p>Call it when the screen opens and whenever the game window resizes
     * &mdash; what Minecraft's {@code Screen.init(width, height)} is for.
     */
    public void resize(float width, float height) {
        Validate.check(width >= 0f && height >= 0f, "a screen's size cannot be negative");
        this.width = width;
        this.height = height;
    }

    /**
     * Describes, measures and lays out the root, then each popup, then works out
     * what is under the cursor and whether its tooltip shows.
     *
     * <p>Every widget is described once by its renderer, however many times its
     * container measures it. A popup whose anchor was not laid out this update
     * &mdash; hidden, removed, or inside a popup that closed &mdash; closes.
     */
    public void update(float mouseX, float mouseY) {
        frame++;
        startDragIfMoved(mouseX, mouseY);
        // Before layout, so whatever is being dragged is drawn under the cursor
        // this frame rather than one behind.
        if (captured != null) {
            captured.mouseDragged(mouseX, mouseY);
        }
        laidOut = root.isVisible();
        if (laidOut) {
            root.layout(Bounds.of(0f, 0f, width, height));
        }
        layoutPopups();
        // A widget that left the screen keeps neither focus nor the mouse.
        if (focused != null && !shown(focused)) {
            focus(null);
        }
        if (captured != null && !shown(captured)) {
            captured = null;
        }
        if (dragSource != null && !shown(dragSource)) {
            endDrag(null);
        }
        hover(widgetAt(mouseX, mouseY));
        updateDrag(mouseX, mouseY);
        updateTooltip(mouseX, mouseY);
    }

    /** Whether a widget is on this screen and was laid out by the last update. */
    private boolean shown(Widget widget) {
        return widget.getScreen() == this && widget.placedIn(frame);
    }

    /** Paints what the last {@link #update} laid out: the root, the popups in order, then the tooltip. */
    public void draw() {
        if (laidOut) {
            root.draw();
        }
        for (Widget popup : popups) {
            popup.draw();
        }
        if (dragGhost != null) {
            dragGhost.draw();
        }
        if (tooltipShown) {
            tooltipNode.draw();
        }
    }

    /**
     * @return the deepest widget at a point, as the last update laid it out,
     *         looking at the newest popup first and the root last; null if
     *         nothing is there
     *
     * <p>Never the tooltip.
     */
    public Widget widgetAt(float x, float y) {
        for (int i = popups.size() - 1; i >= 0; i--) {
            Widget found = popups.get(i).widgetAt(x, y);
            if (found != null) {
                return found;
            }
        }
        return laidOut ? root.widgetAt(x, y) : null;
    }

    long frame() {
        return frame;
    }

    // ---------------------------------------------------------------- input

    /**
     * A mouse button went down.
     *
     * <p>A press outside every open popup closes them all, and goes no further.
     * Otherwise the nearest focusable widget under it takes focus (focus is
     * dropped when there is none), and the press is offered to the widget under
     * the cursor, then each container around it, until one takes it. Disabled
     * widgets are passed over.
     *
     * @return whether anything took it
     */
    public boolean press(float x, float y, MouseButton button) {
        Widget target = widgetAt(x, y);
        if (!popups.isEmpty() && !inPopup(target)) {
            closePopups();
            focus(null);
            return true;
        }
        Widget focusable = null;
        for (Widget node = target; node != null; node = node.parent) {
            if (node.isFocusable() && node.isEnabled()) {
                focusable = node;
                break;
            }
        }
        focus(focusable);
        pressedDraggable = null;
        if (button == MouseButton.LEFT) {
            for (Widget node = target; node != null; node = node.parent) {
                if (node.isEnabled() && node.isDraggable()) {
                    pressedDraggable = node;
                    pressX = x;
                    pressY = y;
                    break;
                }
            }
        }
        for (Widget node = target; node != null; node = node.parent) {
            if (node.isEnabled() && node.mousePressed(x, y, button)) {
                return true;
            }
        }
        return pressedDraggable != null;
    }

    /**
     * A mouse button came up, ending any capture.
     *
     * @return whether a widget had the mouse
     */
    public boolean release(float x, float y, MouseButton button) {
        pressedDraggable = null;
        if (dragSource != null) {
            Widget target = dropTarget;
            if (target != null) {
                target.dropped(dragPayload, x, y);
            }
            endDrag(target);
            return true;
        }
        if (captured == null) {
            return false;
        }
        Widget finished = captured;
        captured = null;
        finished.mouseReleased(x, y, button);
        return true;
    }

    /**
     * The wheel turned, offered to the widget under the cursor and then each
     * container around it. Positive {@code amount} is the wheel turned up.
     *
     * @return whether anything took it
     */
    public boolean scroll(float x, float y, float amount) {
        for (Widget node = widgetAt(x, y); node != null; node = node.parent) {
            if (node.isEnabled() && node.mouseScrolled(x, y, amount)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A key went down or repeated: offered to the focused widget, then each
     * container around it.
     *
     * @return whether anything took it; when nothing did, the key is the
     *         client's to bind
     */
    public boolean key(Key key, Set<Modifier> modifiers) {
        for (Widget node = focused; node != null; node = node.parent) {
            if (node.isEnabled() && node.keyPressed(key, modifiers)) {
                return true;
            }
        }
        return false;
    }

    /** A character was typed. @return whether the focused widget took it */
    public boolean typed(char character) {
        return focused != null && focused.isEnabled() && focused.charTyped(character);
    }

    /**
     * Activates the focused widget: what the client's Enter or Space means.
     *
     * @return whether it did anything
     */
    public boolean activate() {
        return focused != null && focused.isEnabled() && focused.activated();
    }

    /**
     * Backs out one step: drops a drag without dropping it, or else closes the
     * newest popup, or else drops focus. What the client's Escape means.
     *
     * @return whether there was anything to back out of; when not, the client
     *         usually closes the screen
     */
    public boolean cancel() {
        if (dragSource != null) {
            endDrag(null);
            return true;
        }
        if (!popups.isEmpty()) {
            close(popups.get(popups.size() - 1));
            return true;
        }
        if (focused != null) {
            focus(null);
            return true;
        }
        return false;
    }

    /**
     * Moves focus to the next focusable widget, in the order the tree is laid
     * out, wrapping round. While a popup is open, only its widgets are visited.
     *
     * @return whether anything took focus
     */
    public boolean focusNext() {
        return moveFocus(1);
    }

    /** As {@link #focusNext()}, backwards. */
    public boolean focusPrevious() {
        return moveFocus(-1);
    }

    /** Drops keyboard focus. */
    public void clearFocus() {
        focus(null);
    }

    void focus(Widget widget) {
        if (focused == widget) {
            return;
        }
        Widget previous = focused;
        focused = widget;
        if (previous != null) {
            previous.focusLost();
        }
        if (widget != null) {
            widget.focusGained();
        }
    }

    void capture(Widget widget) {
        captured = widget;
    }

    private boolean moveFocus(int step) {
        List<Widget> order = new ArrayList<>();
        Widget layer = popups.isEmpty() ? (laidOut ? root : null) : popups.get(popups.size() - 1);
        if (layer != null) {
            collectFocusable(layer, order);
        }
        if (order.isEmpty()) {
            return false;
        }
        int at = order.indexOf(focused);
        int next = at < 0 ? (step > 0 ? 0 : order.size() - 1) : Math.floorMod(at + step, order.size());
        focus(order.get(next));
        return true;
    }

    private static void collectFocusable(Widget widget, List<Widget> out) {
        if (widget.isFocusable() && widget.isEnabled()) {
            out.add(widget);
        }
        if (widget instanceof Container) {
            for (Widget child : ((Container) widget).getPlaced()) {
                collectFocusable(child, out);
            }
        }
    }

    private boolean inPopup(Widget widget) {
        Widget node = widget;
        while (node != null && node.parent != null) {
            node = node.parent;
        }
        return node != null && popups.contains(node);
    }

    // -------------------------------------------------------- drag and drop

    /**
     * Sets how far, in either direction, the cursor has to move from a press on
     * a draggable widget before a drag starts. A tuning knob, four by default,
     * so a click that wobbles is still a click.
     */
    public Screen dragThreshold(float distance) {
        Validate.check(distance >= 0f, "a drag threshold cannot be negative");
        this.dragThreshold = distance;
        return this;
    }

    /** @return whether a widget is being dragged now. */
    public boolean isDragging() {
        return dragSource != null;
    }

    private void startDragIfMoved(float x, float y) {
        if (pressedDraggable == null || dragSource != null) {
            return;
        }
        if (Math.abs(x - pressX) < dragThreshold && Math.abs(y - pressY) < dragThreshold) {
            return;
        }
        Widget source = pressedDraggable;
        pressedDraggable = null;
        Object payload = source.dragPayload();
        // Asked before this update lays anything out, so "shown" means by the last one.
        if (payload == null || source.getScreen() != this || !source.placedIn(frame - 1)) {
            return;
        }
        // The drag takes over the mouse from whatever the press captured.
        captured = null;
        dragSource = source;
        dragPayload = payload;
        grabX = pressX - source.getBounds().getX();
        grabY = pressY - source.getBounds().getY();
        Widget ghost = source.dragGhost();
        if (ghost != null && ghost.parent == null && ghost.screen == null) {
            ghost.screen = this;
            dragGhost = ghost;
        }
    }

    /** Finds where a drop would land, and lays the ghost out where the source was held. */
    private void updateDrag(float x, float y) {
        if (dragSource == null) {
            return;
        }
        dropTarget = null;
        for (Widget node = hovered; node != null; node = node.parent) {
            if (node != dragSource && node.isEnabled() && node.acceptsDrop(dragPayload)) {
                dropTarget = node;
                break;
            }
        }
        if (dragGhost != null) {
            Size size = dragGhost.naturalSize();
            dragGhost.layout(Bounds.of(x - grabX, y - grabY, size.getWidth(), dragGhost.heightAt(size.getWidth())));
        }
    }

    private void endDrag(Widget target) {
        Widget source = dragSource;
        Object payload = dragPayload;
        dragSource = null;
        dragPayload = null;
        dropTarget = null;
        if (dragGhost != null) {
            dragGhost.screen = null;
            dragGhost.forget();
            dragGhost = null;
        }
        if (source != null) {
            source.dragEnded(payload, target);
        }
    }

    // ------------------------------------------------------------ clipboard

    /**
     * Where text fields copy to and paste from. A clipboard of the screen's own
     * by default; hand in the game's to share it:
     *
     * <pre>{@code
     * screen.clipboard(platform::getClipboard, platform::setClipboard);
     * }</pre>
     */
    public Screen clipboard(Supplier<String> read, Consumer<String> write) {
        this.clipboardRead = Validate.notNull(read, "read");
        this.clipboardWrite = Validate.notNull(write, "write");
        return this;
    }

    /** @return the clipboard's text, never null. */
    public String getClipboard() {
        String text = clipboardRead.get();
        return text == null ? "" : text;
    }

    public void setClipboard(String text) {
        clipboardWrite.accept(text == null ? "" : text);
    }

    private void hover(Widget deepest) {
        for (Widget widget : hoverChain) {
            widget.hovered = false;
        }
        hoverChain.clear();
        hovered = deepest;
        for (Widget node = deepest; node != null; node = node.parent) {
            node.hovered = true;
            hoverChain.add(node);
        }
    }

    // --------------------------------------------------------------- popups

    /**
     * Opens a widget above the screen, placed as {@code placement} says.
     *
     * <p>It is laid out and drawn from the next update, above the root and any
     * popup opened before it, and is hit before them. Close it with
     * {@link #close}; it also closes when its anchor stops being laid out.
     *
     * @return {@code popup}, so it can be opened and kept in one expression
     * @throws IllegalArgumentException if the widget is inside a container, or
     *         already the root of a screen or popup
     */
    public <W extends Widget> W popup(W popup, Popup placement) {
        Validate.notNull(popup, "popup");
        Validate.notNull(placement, "placement");
        Widget node = popup;
        Validate.check(node.parent == null, "a popup cannot be inside a container");
        Validate.check(node.screen == null, "a widget can be the root of only one screen or popup");
        node.screen = this;
        popups.add(node);
        placements.add(placement);
        return popup;
    }

    /**
     * Closes a popup and runs its {@code onClose} listener.
     *
     * @return whether it was open
     */
    public boolean close(Widget popup) {
        int index = popups.indexOf(popup);
        if (index < 0) {
            return false;
        }
        popups.remove(index);
        Popup placement = placements.remove(index);
        popup.screen = null;
        popup.forget();
        placement.closed();
        return true;
    }

    /** Closes every popup, newest first. */
    public void closePopups() {
        for (int i = popups.size() - 1; i >= 0; i--) {
            close(popups.get(i));
        }
    }

    /** @return the open popups, oldest first: the order they are drawn in. */
    public List<Widget> getPopups() {
        return Collections.unmodifiableList(new ArrayList<>(popups));
    }

    /** @return whether a widget is an open popup's root. */
    public boolean isPopup(Widget widget) {
        return popups.contains(widget);
    }

    private void layoutPopups() {
        for (int i = 0; i < popups.size(); i++) {
            Widget popup = popups.get(i);
            Popup placement = placements.get(i);
            Widget anchor = placement.getAnchor();
            if (anchor != null && (anchor.getScreen() != this || !anchor.placedIn(frame))) {
                close(popup);
                i--;
                continue;
            }
            if (!popup.isVisible()) {
                continue;
            }
            popup.layout(placement.place(popup, width, height));
        }
    }

    // -------------------------------------------------------------- tooltip

    /** @return the tooltip, while one is showing; null otherwise. */
    public Tooltip getTooltip() {
        return tooltipShown ? tooltip : null;
    }

    /**
     * Shows the tooltip of the hovered widget, or of the nearest container
     * around it that has one, once the cursor has rested on it for the delay.
     */
    private void updateTooltip(float mouseX, float mouseY) {
        Widget source = null;
        for (Widget node = hovered; node != null; node = node.parent) {
            if (!node.getTooltip().isEmpty()) {
                source = node;
                break;
            }
        }
        if (dragSource != null) {
            source = null;
        }
        if (source != tooltipSource) {
            tooltipSource = source;
            restingSince = now();
        }
        tooltipShown = source != null && now() - restingSince >= tooltipDelay;
        if (!tooltipShown) {
            tooltipNode.forget();
            return;
        }

        tooltip.setText(source.getTooltip());
        Size size = tooltipNode.naturalSize();
        float w = Math.min(Math.min(size.getWidth(), tooltipMaxWidth), width);
        float h = Math.min(tooltipNode.heightAt(w), height);
        float x = mouseX + tooltipOffsetX;
        if (x + w > width) {
            x = mouseX - tooltipOffsetX - w;
        }
        float y = mouseY + tooltipOffsetY;
        if (y + h > height) {
            y = mouseY - tooltipOffsetY - h;
        }
        tooltipNode.layout(Bounds.of(Math.max(0f, Math.min(x, width - w)), Math.max(0f, Math.min(y, height - h)), w, h));
    }
}
