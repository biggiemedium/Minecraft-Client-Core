package dev.px.gui.widget;

import dev.px.core.input.MouseButton;
import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.util.Validate;
import dev.px.gui.container.Column;
import dev.px.gui.container.Stack;
import lombok.Getter;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A titled panel that can be dragged about a {@link Stack} and collapsed to its
 * title.
 *
 * <pre>{@code
 * Stack desktop = new Stack();
 * Window combat = desktop.add(new Window("Combat"), 20f, 20f);
 * combat.add(new Checkbox("Kill Aura", false));
 *
 * look.register(WidgetRenderer.of(Window.class, (window, c) -> {
 *     c.background(PANEL, 4f).min(120f, 0f);
 *     c.row(title -> title.name("title").padding(4f).text(window.getTitle(), TEXT));
 *     c.column(body -> body.padding(4f).slot());
 * }));
 * }</pre>
 *
 * <p>Its children stack as in a {@link Column}. Pressing anywhere on it brings
 * it to the front of its stack. A left press on the renderer's {@code "title"}
 * part drags it &mdash; on the whole window if the renderer names no title &mdash;
 * kept inside the stack; pressing the title with the {@link #collapseButton}
 * (the right button unless set), or anything on the renderer's
 * {@code "collapse"} part, collapses it to its look alone, so its children take
 * no part in layout, drawing or hit testing until it opens again.
 *
 * <p>Dragging and bringing to the front need the window to sit in a
 * {@code Stack}; anywhere else it stays put. Its width is the renderer's to
 * floor with {@code min}, or the children's.
 *
 * <p>Game thread only.
 */
public class Window extends Column {

    @Getter
    private String title;

    @Getter
    private boolean collapsed;

    /** The button that collapses the window from its title. {@code NONE} for none. */
    @Getter
    private MouseButton collapseButton = MouseButton.RIGHT;

    /** Where the cursor held the window, from its corner, while dragging. */
    private float grabX;
    private float grabY;

    public Window(String title) {
        setTitle(title);
    }

    public Window setTitle(String value) {
        this.title = value == null ? "" : value;
        return this;
    }

    public void setCollapsed(boolean value) {
        this.collapsed = value;
    }

    public void toggleCollapsed() {
        this.collapsed = !collapsed;
    }

    /** Sets which button collapses the window from its title; {@code MouseButton.NONE} turns it off. */
    public Window collapseButton(MouseButton button) {
        this.collapseButton = Validate.notNull(button, "button");
        return this;
    }

    @Override
    protected boolean showsChildren() {
        return !collapsed;
    }

    // ---------------------------------------------------------------- input

    @Override
    protected boolean mousePressed(float x, float y, MouseButton button) {
        Stack stack = stack();
        if (stack != null) {
            stack.bringToFront(this);
        }
        Bounds collapse = part("collapse");
        if (collapse != null && collapse.contains(x, y)) {
            toggleCollapsed();
            return true;
        }
        Bounds title = part("title");
        boolean onTitle = title == null || title.contains(x, y);
        if (onTitle && button == collapseButton && collapseButton.isBound()) {
            toggleCollapsed();
            return true;
        }
        if (onTitle && button == MouseButton.LEFT && stack != null) {
            grabX = x - getBounds().getX();
            grabY = y - getBounds().getY();
            capture();
        }
        return true;
    }

    /** Follows the cursor, kept inside the stack. */
    @Override
    protected void mouseDragged(float x, float y) {
        Stack stack = stack();
        if (stack == null) {
            return;
        }
        Bounds room = stack.getSlot();
        Bounds at = getBounds();
        float left = clamp(x - grabX - room.getX(), room.getWidth() - at.getWidth());
        float top = clamp(y - grabY - room.getY(), room.getHeight() - at.getHeight());
        stack.move(this, left, top);
    }

    private Stack stack() {
        return getParent() instanceof Stack ? (Stack) getParent() : null;
    }

    private static float clamp(float offset, float furthest) {
        return Math.max(0f, Math.min(offset, Math.max(0f, furthest)));
    }

    // --------------------------------------------------------------- chaining

    @Override
    public Window gap(float spacing) {
        super.gap(spacing);
        return this;
    }

    @Override
    public Window align(Align across) {
        super.align(across);
        return this;
    }

    @Override
    public Window visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Window enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Window grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Window tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Window tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
