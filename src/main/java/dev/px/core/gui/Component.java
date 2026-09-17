package dev.px.core.gui;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.layout.Shape;
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
 * <p>One method is mandatory, and it is the same one a
 * {@link dev.px.core.hud.HudElement} writes: describe what you are made of. Your
 * height is what that measures and your appearance is that same description
 * drawn, so the two cannot drift apart. Stacking, hit routing, drag tracking,
 * focus and clipping are handled above this class, so writing a component never
 * means reading the layout or the input code.
 *
 * <pre>{@code
 * public final class Divider extends Component {
 *
 *     @Override protected void content(Content c) {
 *         c.rect(0f, 1f, GuiStyle.outline());
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

    /** This component's own content, as last laid out. */
    private Content placed;

    /** How tall that content is, which is not the same as {@link #bounds} once children are stacked. */
    private float contentHeight;

    // ------------------------------------------------------------- contract

    /**
     * Describes what this component is made of, once per layout.
     *
     * <p>The only method most components write. Its height is the component's
     * height and its parts are what gets drawn, so the two cannot disagree
     * &mdash; the same contract {@link dev.px.core.hud.HudElement} makes, and for
     * the same reason. A component that draws nothing of its own, such as a plain
     * container, leaves it alone.
     *
     * <p>Two things matter here that do not in a HUD element, because a component
     * is given a width rather than sizing to its content:
     *
     * <ul>
     *   <li>{@link Content#fill()} absorbs the slack, which is how a row puts
     *       its label on the left and its value on the right.</li>
     *   <li>{@link Content#custom(String, float, float, dev.px.core.hud.Draw)}
     *       names a part so {@link #part} can find it again. A widget that maps a
     *       click onto something it drew &mdash; a slider track, a toggle knob
     *       &mdash; asks for that rectangle rather than working it out a second
     *       time and getting it subtly wrong.</li>
     * </ul>
     *
     * <pre>{@code
     * @Override protected void content(Content c) {
     *     c.height(GuiStyle.rowHeight()).padding(GuiStyle.padding()).align(Align.CENTER);
     *     c.background(GuiStyle.surface(), GuiStyle.radius());
     *     c.row(r -> {
     *         r.align(Align.CENTER);
     *         r.text(label, GuiStyle.text());
     *         r.fill();
     *         r.custom("knob", 14f, 7f, this::drawKnob);
     *     });
     * }
     * }</pre>
     *
     * <p>Called every layout and never cached: a dropdown is taller while open, a
     * group taller while expanded, and neither goes through anywhere that could
     * invalidate a cache.
     */
    protected void content(Content content) {
    }

    /**
     * Fills the component's whole resolved rectangle, behind its content and its
     * children.
     *
     * <p>The one thing {@link #content} cannot express, because it is measured
     * before the children are laid out and so does not know the final height. A
     * window background is the case this exists for; almost nothing else needs
     * it.
     */
    protected void renderBackdrop(float x, float y, float w, float h) {
    }

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
     * @param width the width this component will be laid out at
     * @return the height it needs
     *
     * <p>Measured from {@link #content}, so there is nothing to keep in step.
     */
    public float getPreferredHeight(float width) {
        return describe(width).size().getHeight();
    }

    /**
     * Builds and measures this component's content at a given width.
     *
     * <p>Rebuilt rather than cached, for the reason everything else in this
     * package is: what a component is made of changes between frames.
     */
    protected final Content describe(float width) {
        Content content = Content.column();
        content.width(width);
        content(content);
        content.measure();
        return content;
    }

    /**
     * Places this component and everything under it.
     *
     * <p>The default puts the component at the given position, at the given
     * width, at whatever height its content measured, and leaves its children
     * unplaced. {@link Panel} overrides it to stack them. A component with
     * children and no stacking behaviour of its own should extend {@code Panel}
     * rather than reimplement this.
     */
    public void layout(float x, float y, float width) {
        Content content = describe(width);
        float height = content.size().getHeight();
        placeContent(content, x, y, width, height);
        setBounds(Bounds.of(x, y, width, height));
    }

    /**
     * Records the laid-out content, so drawing and {@link #part} use the geometry
     * layout already found rather than working it out again.
     */
    protected final void placeContent(Content content, float x, float y, float width, float height) {
        content.layout(x, y, width, height);
        this.placed = content;
        this.contentHeight = height;
    }

    /**
     * @return the rectangle a named part of this component's content ended up in,
     *         or null
     *
     * <p>Valid once the component has been laid out. What a click handler asks so
     * that the region it responds to is exactly the region it drew.
     */
    public final Bounds part(String name) {
        return placed == null ? null : placed.find(name);
    }

    /** @return whether a point is inside the named part. */
    public final boolean hitsPart(String name, float x, float y) {
        Bounds at = part(name);
        return at != null && at.contains(x, y);
    }

    protected final void setBounds(Bounds resolved) {
        this.bounds = resolved == null ? Bounds.EMPTY : resolved;
    }

    // ------------------------------------------------------ engine internals

    /**
     * Draws this component and then its visible children, in order.
     *
     * <p>Final: the draw order is a property of the tree, not something an
     * individual component gets to redefine. The backdrop goes down first, then
     * this component's own content, then the children on top.
     */
    public final void renderTree() {
        if (!isVisible()) {
            return;
        }
        renderBackdrop(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
        if (placed != null) {
            // Drawn at the height its content measured, not the component's full
            // height: a panel is as tall as its children, its own row is not.
            placed.draw(bounds.getX(), bounds.getY(), bounds.getWidth(), contentHeight);
        }
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
