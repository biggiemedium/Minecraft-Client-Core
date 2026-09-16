package dev.px.core.gui;

import dev.px.core.hud.Bounds;
import dev.px.core.hud.Shape;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One node of the GUI tree.
 *
 * <p>Two methods are mandatory: how tall you are at a given width, and how to
 * draw yourself. That is the whole contract, and it is deliberately the whole
 * contract &mdash; the same promise {@link dev.px.core.hud.HudElement} makes.
 * Stacking, hit routing, drag tracking, focus and clipping are handled above
 * this class, so writing a component never means reading the layout or the input
 * code.
 *
 * <pre>{@code
 * public final class Divider extends Component {
 *
 *     @Override public float getPreferredHeight(float width) { return 1f; }
 *
 *     @Override public void render(float x, float y, float w, float h) {
 *         Render.rect(x, y, w, h, GuiStyle.outline());
 *     }
 * }
 * }</pre>
 *
 * <p>The input methods all default to "not handled" and return a boolean saying
 * whether they consumed the event, so overriding one is opt-in and overriding
 * none is legal. A component is only offered an event that landed inside it.
 *
 * <p><b>Children are drawn by the engine, not by you.</b> {@link #render} draws
 * the component itself; {@link #renderTree()} then walks the visible children.
 * A component that looped its own children would draw them twice.
 *
 * <p><b>Hit testing is a {@link Shape}</b>, so a round or triangular component
 * gets the right clickable region by overriding one method, exactly as a HUD
 * element does. A point must be inside a parent to reach a child, which is what
 * keeps routing to a single walk down the tree.
 */
public abstract class Component {

    /** The node this was added to, or null for a root. */
    @Getter
    private Component parent;

    private final List<Component> children = new ArrayList<>(0);

    /** Assigned by {@link #layout}; meaningless before the first frame. */
    @Getter
    private Bounds bounds = Bounds.EMPTY;

    // ------------------------------------------------------------- contract

    /**
     * @param width the width this component will be laid out at
     * @return the height it needs
     *
     * <p>Expected to change between frames: a dropdown is taller while open, a
     * group taller while expanded. Nothing caches it.
     */
    public abstract float getPreferredHeight(float width);

    /**
     * Draws the component with its top-left corner at {@code (x, y)}.
     *
     * <p>Children are drawn afterwards by the engine, so anything drawn here
     * lands behind them. That is what makes a panel background work.
     */
    public abstract void render(float x, float y, float w, float h);

    /**
     * The clickable region within the given rectangle.
     *
     * <p>Defaults to the whole rectangle, which is right for almost everything.
     * Override it and hit testing, not just drawing, follows the real shape.
     */
    public Shape shape(float x, float y, float w, float h) {
        return Shape.rect(x, y, w, h);
    }

    /**
     * @return whether this component takes part in layout, drawing and hit
     *         testing at all
     *
     * <p>A hidden component is skipped everywhere, so a row hidden by
     * {@code visibleWhen} cannot be clicked through the gap it left.
     */
    public boolean isVisible() {
        return true;
    }

    /**
     * @return whether this component's children take part at all
     *
     * <p>How everything collapsible works: a collapsed group, a closed dropdown
     * and a minimised window all return false, and their children then vanish
     * from layout, drawing and hit testing together. Gating it in one place is
     * what stops a collapsed row from still answering a click at the bounds it
     * held before it closed.
     */
    protected boolean showsChildren() {
        return true;
    }

    /** Text shown when the cursor rests on this component. Empty for none. */
    public String getTooltip() {
        return "";
    }

    // ---------------------------------------------------------------- input

    /**
     * A press that landed on this component.
     *
     * @return whether it was consumed; returning false offers it to the parent
     */
    protected boolean onClick(float x, float y, MouseButton button) {
        return false;
    }

    /**
     * The cursor moved while this component was dragging.
     *
     * <p>Only called after {@link Screen#beginDrag}, and called from the frame
     * loop rather than from an event: Core has no mouse-move event and a drag is
     * only interesting while something is being drawn. Same arrangement the HUD
     * editor uses.
     */
    protected void onDrag(float x, float y) {
    }

    /** The button came up, ending a drag this component started. */
    protected void onRelease(float x, float y) {
    }

    /**
     * A key press, delivered only while this component holds focus.
     *
     * @return whether it was consumed
     */
    protected boolean onKey(Key key, Set<Modifier> modifiers) {
        return false;
    }

    /** A typed character, delivered only while this component holds focus. */
    protected boolean onChar(char character) {
        return false;
    }

    /** A wheel movement over this component. Positive scrolls up. */
    protected boolean onScroll(float amount, float x, float y) {
        return false;
    }

    /** Called when focus moves away, so a text field can commit what was typed. */
    protected void onFocusLost() {
    }

    // ----------------------------------------------------------------- tree

    /** @return {@code child}, so it can be added and kept in one expression. */
    public final <C extends Component> C add(C child) {
        if (child == null || child == this) {
            return child;
        }
        // Through a Component reference, not through C: a private field is not
        // reachable across a type variable even inside its own class.
        Component node = child;
        if (node.parent != null) {
            node.parent.remove(node);
        }
        node.parent = this;
        children.add(node);
        return child;
    }

    public final void remove(Component child) {
        if (child != null && children.remove(child)) {
            child.parent = null;
        }
    }

    public final void clearChildren() {
        for (Component child : children) {
            child.parent = null;
        }
        children.clear();
    }

    /** @return an unmodifiable view of every child, in draw order. */
    public final List<Component> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * @return only the children currently taking part
     *
     * <p>The single answer used by layout, drawing and hit testing alike, so the
     * three can never disagree about whether a row exists.
     */
    public final List<Component> visibleChildren() {
        if (!showsChildren()) {
            return Collections.emptyList();
        }
        List<Component> visible = new ArrayList<>(children.size());
        for (Component child : children) {
            if (child.isVisible()) {
                visible.add(child);
            }
        }
        return visible;
    }

    /**
     * Moves a child to the end of the list, so it draws last and is hit first.
     * What clicking a window does.
     */
    public final void bringToFront(Component child) {
        if (children.remove(child)) {
            children.add(child);
        }
    }

    /** @return the {@link Screen} this component sits under, or null if unattached. */
    public final Screen getScreen() {
        Component node = this;
        while (node != null) {
            if (node instanceof Screen) {
                return (Screen) node;
            }
            node = node.parent;
        }
        return null;
    }

    public final boolean isFocused() {
        Screen screen = getScreen();
        return screen != null && screen.getFocused() == this;
    }

    /** Takes keyboard focus, so {@link #onKey} and {@link #onChar} start arriving. */
    protected final void focus() {
        Screen screen = getScreen();
        if (screen != null) {
            screen.focus(this);
        }
    }

    // --------------------------------------------------------------- layout

    /**
     * Places this component and everything under it.
     *
     * <p>The default puts the component at the given position, at the given
     * width, at whatever height it asked for, and leaves its children unplaced.
     * {@link Panel} overrides it to stack them. A component with children and no
     * stacking behaviour of its own should extend {@code Panel} rather than
     * reimplement this.
     */
    public void layout(float x, float y, float width) {
        setBounds(Bounds.of(x, y, width, getPreferredHeight(width)));
    }

    protected final void setBounds(Bounds resolved) {
        this.bounds = resolved == null ? Bounds.EMPTY : resolved;
    }

    // ------------------------------------------------------ engine internals

    /**
     * Draws this component and then its visible children, in order.
     *
     * <p>Final: the draw order is a property of the tree, not something an
     * individual component gets to redefine.
     */
    public final void renderTree() {
        if (!isVisible()) {
            return;
        }
        render(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
        for (Component child : visibleChildren()) {
            child.renderTree();
        }
    }

    /** @return this component's interaction shape at its resolved bounds. */
    public final Shape resolvedShape() {
        return shape(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    public final boolean hits(float x, float y) {
        return isVisible() && resolvedShape().contains(x, y);
    }

    /**
     * @return the deepest visible component containing the point, or null
     *
     * <p>Children are searched last-first, so what was drawn on top wins, and a
     * point outside this component never reaches its children &mdash; containment
     * is hierarchical, which is what keeps routing to one walk rather than a scan
     * of every node.
     */
    public final Component componentAt(float x, float y) {
        if (!hits(x, y)) {
            return null;
        }
        List<Component> visible = visibleChildren();
        for (int i = visible.size() - 1; i >= 0; i--) {
            Component found = visible.get(i).componentAt(x, y);
            if (found != null) {
                return found;
            }
        }
        return this;
    }

    /**
     * Offers a press to the deepest component under the point, then to each
     * ancestor in turn until one consumes it.
     *
     * @return the component that handled it, or null if nothing did
     */
    public final Component dispatchClick(float x, float y, MouseButton button) {
        Component target = componentAt(x, y);
        while (target != null) {
            if (target.onClick(x, y, button)) {
                return target;
            }
            target = target.parent;
        }
        return null;
    }

    /** As {@link #dispatchClick}, for the wheel. */
    public final Component dispatchScroll(float amount, float x, float y) {
        Component target = componentAt(x, y);
        while (target != null) {
            if (target.onScroll(amount, x, y)) {
                return target;
            }
            target = target.parent;
        }
        return null;
    }
}
