package dev.px.gui;

import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.layout.Shape;
import dev.px.core.layout.Size;
import dev.px.core.util.Validate;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One node of a screen's tree: state and geometry, and nothing drawn.
 *
 * <pre>{@code
 * Button play = new Button("Singleplayer").onPress(adapter::singleplayer);
 * play.enabledWhen(adapter::canPlay);
 * menu.add(play);
 * }</pre>
 *
 * <p>A widget holds its state &mdash; hovered, visible, enabled, and whatever a
 * subclass adds, such as a button's label &mdash; and, once its {@link Screen}
 * has updated, where it was laid out: its bounds, the named parts its renderer
 * described, and its hit shape. <b>It draws nothing and listens to nothing.</b>
 * How it looks is the {@link dev.px.gui.render.WidgetRenderer} the client
 * registered for its type, which describes it with {@link Content}; the library
 * measures, lays out, hit tests and draws that one description.
 *
 * <p>A leaf has no children. A button's icon and label are parts its renderer
 * describes, not widgets of their own; only a {@link Container} holds children.
 *
 * <p>Subclasses add state and actions (a button's {@code press()}), and report
 * changes to listeners registered on the widget. Their state stays public, so a
 * client may poll it instead.
 *
 * <p>Game thread only.
 */
public abstract class Widget {

    private static final BooleanSupplier ALWAYS = () -> true;

    /** The container this was added to, or null for a root or a widget not yet added. */
    @Getter
    Container parent;

    /** The screen this is the root of, or null. Every other widget finds it through its root. */
    Screen screen;

    private static final Supplier<String> NO_TOOLTIP = () -> "";

    private BooleanSupplier visibleWhen = ALWAYS;
    private BooleanSupplier enabledWhen = ALWAYS;

    /** This widget's share of leftover space in a row or column. Zero takes none. */
    @Getter
    private float grow;

    private Supplier<String> tooltip = NO_TOOLTIP;

    /** What dragging this widget carries, or null when it can't be dragged. */
    private Supplier<?> dragPayload;

    /** What is drawn under the cursor while it is dragged, or null for nothing. */
    private Supplier<? extends Widget> dragGhost;

    /** Whether a dragged payload may be dropped here, or null when nothing can be. */
    private Predicate<Object> dropAccepts;
    private Consumer<Object> dropHandler;

    /** Set by the screen each update: under the cursor, or a container of what is. */
    boolean hovered;

    /** Where the last update placed this widget. Empty before the first. */
    @Getter
    private Bounds bounds = Bounds.EMPTY;

    private Map<String, Bounds> parts = Collections.emptyMap();

    /** The renderer's hit shape, or null for the bounds. */
    private Shape shape;

    /** This frame's description, kept so a widget is described once a frame however often it is measured. */
    private Content content;
    private long describedFrame = -1L;

    /** The update that last placed this widget, so a popup can tell whether its anchor is showing. */
    private long placedFrame = -1L;

    /**
     * This frame's last measurements, so a container measuring a child again
     * &mdash; for its own height, then to arrange it &mdash; costs nothing.
     * Without it a deep tree is measured a number of times that doubles with
     * every level.
     */
    private long measuredFrame = -1L;
    private float measuredWidth = Float.NaN;
    private float measuredHeight;
    private long naturalFrame = -1L;
    private Size natural;

    // ---------------------------------------------------------------- state

    /**
     * Shows this widget only while {@code condition} holds, read live.
     *
     * <p>A hidden widget takes no part in layout, drawing or hit testing, and
     * neither does anything inside it.
     */
    public Widget visibleWhen(BooleanSupplier condition) {
        this.visibleWhen = Validate.notNull(condition, "condition");
        return this;
    }

    /**
     * Enables this widget only while {@code condition} holds, read live.
     *
     * <p>A disabled widget is still laid out and drawn &mdash; its renderer reads
     * {@link #isEnabled()} to show it greyed &mdash; but its actions refuse, and
     * so do those of everything inside it.
     */
    public Widget enabledWhen(BooleanSupplier condition) {
        this.enabledWhen = Validate.notNull(condition, "condition");
        return this;
    }

    /**
     * Asks for a share of the space left over in a {@code Row} or {@code Column}.
     *
     * <p>A growing child takes its measured size plus its weight's share of
     * whatever room is left, and gives up its share when there is less room
     * than everything measured. That is how a list fills the rest of a window,
     * and how a {@code Scroll} gets a viewport shorter than its content. Zero,
     * the default, keeps the measured size. Containers that don't arrange along
     * a line ignore it.
     */
    public Widget grow(float weight) {
        Validate.check(weight >= 0f, "a grow weight cannot be negative");
        this.grow = weight;
        return this;
    }

    /**
     * Text shown beside the cursor once it rests on this widget.
     *
     * <p>Shown by the screen after its tooltip delay, in a
     * {@link dev.px.gui.widget.Tooltip} the client's look renders. Hovering
     * something inside this widget that has no tooltip of its own shows this one.
     */
    public Widget tooltip(String text) {
        String fixed = text == null ? "" : text;
        this.tooltip = () -> fixed;
        return this;
    }

    /** As {@link #tooltip(String)}, read each time it is shown. */
    public Widget tooltip(Supplier<String> text) {
        this.tooltip = Validate.notNull(text, "text");
        return this;
    }

    /** @return this widget's tooltip text, empty for none. */
    public String getTooltip() {
        String text = tooltip.get();
        return text == null ? "" : text;
    }

    // ------------------------------------------------------- drag and drop

    /**
     * Lets this widget be dragged onto another, carrying what {@code payload}
     * gives when the drag starts.
     *
     * <pre>{@code
     * row.draggable(() -> module);                                  // a module row, dragged
     * favourites.acceptsDrops(Module.class, favourites::addModule); // and dropped here
     * }</pre>
     *
     * <p>A drag starts once the cursor has moved the screen's
     * {@linkplain Screen#dragThreshold drag threshold} from a left press on the
     * widget, so a plain click is still a click. A payload of null cancels it.
     */
    public Widget draggable(Supplier<?> payload) {
        return draggable(payload, null);
    }

    /**
     * As {@link #draggable(Supplier)}, with a widget drawn under the cursor while
     * it is dragged &mdash; usually a copy of how this one looks, which the
     * client's look renders like any other widget. It keeps the offset from the
     * cursor that the press had, as though this widget were lifted, and is never
     * hit.
     */
    public Widget draggable(Supplier<?> payload, Supplier<? extends Widget> ghost) {
        this.dragPayload = Validate.notNull(payload, "payload");
        this.dragGhost = ghost;
        return this;
    }

    /**
     * Accepts payloads of a type dropped here, handing each to {@code onDrop}.
     *
     * <p>While something droppable here is dragged over this widget, or over
     * something inside it that doesn't accept it, {@link #isDropTarget()} is true.
     */
    public <T> Widget acceptsDrops(Class<T> type, Consumer<? super T> onDrop) {
        return acceptsDrops(type, payload -> true, onDrop);
    }

    /** As {@link #acceptsDrops(Class, Consumer)}, only for payloads {@code when} allows. */
    public <T> Widget acceptsDrops(Class<T> type, Predicate<? super T> when,
                                   Consumer<? super T> onDrop) {
        Validate.notNull(type, "type");
        Validate.notNull(when, "when");
        Validate.notNull(onDrop, "onDrop");
        this.dropAccepts = payload -> type.isInstance(payload) && when.test(type.cast(payload));
        this.dropHandler = payload -> onDrop.accept(type.cast(payload));
        return this;
    }

    /** @return whether this widget is being dragged now. */
    public boolean isDragged() {
        Screen host = getScreen();
        return host != null && host.getDragSource() == this;
    }

    /** @return whether what is being dragged would be dropped on this widget if let go now. */
    public boolean isDropTarget() {
        Screen host = getScreen();
        return host != null && host.getDropTarget() == this;
    }

    /** @return whether a left press here can start a drag. Overridable, as are the hooks below. */
    protected boolean isDraggable() {
        return dragPayload != null;
    }

    /** @return what a drag starting now carries; null cancels it. */
    protected Object dragPayload() {
        return dragPayload == null ? null : dragPayload.get();
    }

    /** @return the widget drawn under the cursor while this is dragged, or null. */
    protected Widget dragGhost() {
        return dragGhost == null ? null : dragGhost.get();
    }

    /** @return whether this widget takes {@code payload} if it is dropped here. */
    protected boolean acceptsDrop(Object payload) {
        return dropAccepts != null && dropAccepts.test(payload);
    }

    /** Something this widget accepts was dropped on it, at a point. */
    protected void dropped(Object payload, float x, float y) {
        if (dropHandler != null) {
            dropHandler.accept(payload);
        }
    }

    /**
     * A drag of this widget ended: dropped on {@code target}, or cancelled or let
     * go over nothing that took it when {@code target} is null.
     */
    protected void dragEnded(Object payload, Widget target) {
    }

    /** @return whether this widget's own condition shows it. */
    public boolean isVisible() {
        return visibleWhen.getAsBoolean();
    }

    /** @return whether this widget and every container around it are enabled. */
    public boolean isEnabled() {
        for (Widget node = this; node != null; node = node.parent) {
            if (!node.enabledWhen.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return whether the cursor was over this widget at the last update
     *
     * <p>True for the deepest widget under the cursor and for every container
     * around it, so a row reads as hovered while the cursor is on its label.
     * {@link Screen#getHovered()} names the deepest one.
     */
    public boolean isHovered() {
        return hovered;
    }

    // ------------------------------------------------------------- geometry

    /**
     * @return the rectangle a named part was laid out in at the last update, or
     *         null
     *
     * <p>Part names are a widget type's contract with its renderer: a slider
     * reads the part named {@code "track"}, so what is drawn and what is clicked
     * are the same rectangle.
     */
    public Bounds part(String name) {
        return parts.get(name);
    }

    /** @return every named part and where it was laid out, in the order described. */
    public Map<String, Bounds> parts() {
        return parts;
    }

    /** @return the clickable region: the renderer's shape, or the bounds. */
    public Shape getShape() {
        return shape != null ? shape : Shape.rect(bounds);
    }

    /** @return whether a point is inside this widget's clickable region. */
    public boolean hits(float x, float y) {
        return isVisible() && getShape().contains(x, y);
    }

    /** @return the screen this widget's tree is shown on, or null. */
    public Screen getScreen() {
        Widget node = this;
        while (node.parent != null) {
            node = node.parent;
        }
        return node.screen;
    }

    // ---------------------------------------------------------------- input

    /**
     * @return whether this widget takes keyboard focus: a press on it focuses
     *         it, and {@link Screen#focusNext()} stops at it
     *
     * <p>False by default; a text field or anything driven by keys says true.
     */
    public boolean isFocusable() {
        return false;
    }

    /** @return whether this widget holds its screen's keyboard focus. */
    public boolean isFocused() {
        Screen host = getScreen();
        return host != null && host.getFocused() == this;
    }

    /**
     * @return whether a press on this widget is still held: it captured the
     *         mouse and the button has not come up
     *
     * <p>For a renderer showing a button pushed in, or a slider being dragged.
     */
    public boolean isPressed() {
        Screen host = getScreen();
        return host != null && host.getCaptured() == this;
    }

    /** Takes keyboard focus, so {@link #keyPressed} and {@link #charTyped} start arriving. */
    protected final void focus() {
        Screen host = getScreen();
        if (host != null) {
            host.focus(this);
        }
    }

    /**
     * Captures the mouse until the button comes up: {@link #mouseDragged} is then
     * called every update with the cursor, and {@link #mouseReleased} when it
     * comes up, wherever the cursor is. Call it from {@link #mousePressed}.
     */
    protected final void capture() {
        Screen host = getScreen();
        if (host != null) {
            host.capture(this);
        }
    }

    /**
     * A press that landed on this widget, or on something inside it that
     * didn't take it.
     *
     * @return whether it was taken; false offers it to the container around
     */
    protected boolean mousePressed(float x, float y, MouseButton button) {
        return false;
    }

    /** The cursor, every update, while this widget has the mouse {@linkplain #capture captured}. */
    protected void mouseDragged(float x, float y) {
    }

    /** The button came up, ending a capture this widget started. */
    protected void mouseReleased(float x, float y, MouseButton button) {
    }

    /**
     * The wheel turned over this widget, or over something inside it that didn't
     * take it. Positive {@code amount} is the wheel turned up.
     *
     * @return whether it was taken
     */
    protected boolean mouseScrolled(float x, float y, float amount) {
        return false;
    }

    /**
     * A key went down or repeated while this widget, or something inside it,
     * held focus.
     *
     * @return whether it was taken; false offers it to the container around
     */
    protected boolean keyPressed(Key key, Set<Modifier> modifiers) {
        return false;
    }

    /** A character was typed while this widget held focus. @return whether it was taken */
    protected boolean charTyped(char character) {
        return false;
    }

    /**
     * The focused widget was activated through {@link Screen#activate()}: what
     * the client's Enter or Space means. A button presses, a checkbox toggles.
     *
     * @return whether it did anything
     */
    protected boolean activated() {
        return false;
    }

    /** Called when this widget takes focus. */
    protected void focusGained() {
    }

    /** Called when focus moves away, so a field can tidy its selection. */
    protected void focusLost() {
    }

    // --------------------------------------------------------------- engine

    /**
     * This frame's description: what the widget's renderer made of it, described
     * once a frame and reused by every measurement.
     *
     * <p>A widget with no renderer, or whose renderer threw, gets an empty
     * description, to which a container adds its slot.
     */
    final Content describe() {
        Screen host = getScreen();
        long frame = host == null ? -1L : host.frame();
        if (content != null && host != null && describedFrame == frame) {
            return content;
        }
        Content fresh = Content.column();
        if (host == null || !host.getRenderers().describe(this, fresh)) {
            // A renderer that threw may have left half a description; start again.
            fresh = Content.column();
        }
        content = fresh;
        describedFrame = frame;
        return fresh;
    }

    /** @return the height at a width, measured once a frame per width. */
    final float heightAt(float width) {
        Screen host = getScreen();
        long frame = host == null ? -1L : host.frame();
        if (host != null && measuredFrame == frame && measuredWidth == width) {
            return measuredHeight;
        }
        measuredHeight = measure(width);
        measuredWidth = width;
        measuredFrame = frame;
        return measuredHeight;
    }

    /** @return the natural size, measured once a frame. */
    final Size naturalSize() {
        Screen host = getScreen();
        long frame = host == null ? -1L : host.frame();
        if (host != null && naturalFrame == frame && natural != null) {
            return natural;
        }
        natural = measureNatural();
        naturalFrame = frame;
        return natural;
    }

    /** @return the height this widget needs at a given width. */
    float measure(float width) {
        Content described = describe();
        described.width(width);
        return described.measure().getHeight();
    }

    /** @return the size this widget's description wants, given no width. */
    Size measureNatural() {
        Content described = describe();
        described.width(0f);
        return described.measure();
    }

    /** Places this widget and keeps where its parts and shape ended up. */
    void layout(Bounds at) {
        Content described = describe();
        described.width(at.getWidth());
        described.measure();
        described.layout(at.getX(), at.getY(), at.getWidth(), at.getHeight());
        placed(at, described);
    }

    /** Records a finished layout. Called by {@link #layout} and by a container's own. */
    final void placed(Bounds at, Content described) {
        Screen host = getScreen();
        this.placedFrame = host == null ? -1L : host.frame();
        this.bounds = at;
        this.parts = described.namedParts();
        this.shape = host == null ? null : host.getRenderers().shapeOf(this, at);
    }

    /** @return whether the given update placed this widget. */
    final boolean placedIn(long frame) {
        return placedFrame == frame;
    }

    /** Paints what the last update laid out. */
    void draw() {
        if (content != null) {
            content.draw(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
        }
    }

    /**
     * @return the deepest widget at the point, as last laid out, or null
     *
     * <p>Goes by the last update's geometry alone, so between updates a widget is
     * hit exactly where it was drawn.
     */
    Widget widgetAt(float x, float y) {
        return getShape().contains(x, y) ? this : null;
    }

    /** Forgets layout, so a widget taken out of a tree keeps no stale geometry. */
    void forget() {
        bounds = Bounds.EMPTY;
        parts = Collections.emptyMap();
        shape = null;
        content = null;
        natural = null;
        measuredFrame = -1L;
        naturalFrame = -1L;
        placedFrame = -1L;
        hovered = false;
    }
}
