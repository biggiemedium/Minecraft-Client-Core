package dev.px.gui;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.layout.Size;
import dev.px.core.render.Render;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A widget with children, arranged inside the slot its renderer describes.
 *
 * <pre>{@code
 * look.register(WidgetRenderer.of(Column.class, (column, c) -> {
 *     c.background(PANEL, 4f).padding(6f);
 *     c.slot();                                  // the children go here
 * }));
 *
 * Column menu = new Column().gap(4f);
 * menu.add(new Button("Singleplayer"));
 * }</pre>
 *
 * <p>The renderer draws the container's own look &mdash; a background, a title
 * bar, a border &mdash; and says with {@link Content#slot()} where the children
 * go. The library sizes the slot from the children and places them in the
 * rectangle it lands in, so the look and the children cannot disagree:
 *
 * <ol>
 *   <li>The description is measured with the slot empty at the container's
 *       width and laid out, which says how wide the slot is.</li>
 *   <li>The children are measured at that width.</li>
 *   <li>The slot is given their size, measured again and laid out, and the
 *       children are arranged inside it.</li>
 * </ol>
 *
 * <p>A description without a slot gets one at its end, so the children follow
 * whatever was described. A container with no renderer at all is silent rather
 * than warned about, and is laid out as a bare slot: a plain layout column is
 * common, and has no look to miss.
 *
 * <p>How the children are arranged is the subclass's: three methods, written
 * with {@link #heightOf}, {@link #naturalSizeOf} and {@link #place}.
 *
 * <p>Game thread only.
 */
public abstract class Container extends Widget {

    private final List<Widget> children = new ArrayList<>(4);

    /** The children placed by the last layout, in draw order: what drawing and hit testing use. */
    private List<Widget> placed = Collections.emptyList();

    /** The children {@link #place} is called for during an {@link #arrange}. */
    private final List<Widget> placing = new ArrayList<>(4);

    // ----------------------------------------------------------------- tree

    /**
     * Adds a child at the end, moving it out of any container it was in.
     *
     * @return {@code child}, so it can be added and kept in one expression
     * @throws IllegalArgumentException if the child is this container, a container
     *         around it, or the root of a screen
     */
    public <W extends Widget> W add(W child) {
        Validate.notNull(child, "child");
        Widget node = child;
        Validate.check(node.screen == null, "a screen's root cannot be added to a container");
        for (Widget around = this; around != null; around = around.parent) {
            Validate.check(around != node, "a container cannot be added inside itself");
        }
        if (node.parent != null) {
            node.parent.remove(node);
        }
        node.parent = this;
        children.add(node);
        return child;
    }

    /** Takes a child out. It keeps no layout from this tree. */
    public void remove(Widget child) {
        if (child != null && children.remove(child)) {
            child.parent = null;
            child.forget();
        }
    }

    public void clear() {
        for (Widget child : new ArrayList<>(children)) {
            remove(child);
        }
    }

    /**
     * Moves a child to the end, so it is drawn last and hit first: what pressing
     * a window does.
     */
    public void bringToFront(Widget child) {
        if (children.remove(child)) {
            children.add(child);
        }
    }

    /** @return every child, in draw order. */
    public List<Widget> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * @return the children the last update placed, in draw order
     *
     * <p>What drawing and hit testing go by, so a child hidden after the update
     * is still drawn and hit where it was until the next one, rather than
     * half-disappearing. A container may leave children out: a {@code Scroll}
     * places only the rows in view.
     */
    public List<Widget> getPlaced() {
        return placed;
    }

    // ----------------------------------------------------------- arranging

    /**
     * @return the height the visible children need, arranged at {@code width}
     */
    protected abstract float childrenHeight(List<Widget> visible, float width);

    /** @return the size the visible children want, given no width. */
    protected abstract Size childrenNatural(List<Widget> visible);

    /**
     * Places the visible children inside {@code slot}, with {@link #place}.
     *
     * <p>The slot is the size {@link #childrenHeight} asked for, larger when the
     * container was given more room than it measured, and smaller when it was
     * given less. Children are drawn and hit in the order they are placed, and
     * a child not placed is neither.
     */
    protected abstract void arrange(List<Widget> visible, Bounds slot);

    /**
     * @return whether the children take part at all
     *
     * <p>How collapsing works: a collapsed window returns false, and its
     * children leave layout, drawing and hit testing together, so nothing can be
     * clicked where a collapsed row used to be.
     */
    protected boolean showsChildren() {
        return true;
    }

    /**
     * @return the rectangle children are clipped to, for drawing and for hit
     *         testing, or null for none
     *
     * <p>None by default: a child may stick out of its container. A
     * {@code Scroll} clips to its viewport, so a row scrolled half out of view
     * is cut off at the edge and cannot be clicked where it isn't shown.
     */
    protected Bounds clipChildren() {
        return null;
    }

    /** @return the height a child needs at a width. For {@link #childrenHeight} and {@link #arrange}. */
    protected static float heightOf(Widget child, float width) {
        return child.heightAt(width);
    }

    /** @return the size a child wants, given no width. For {@link #childrenNatural}. */
    protected static Size naturalSizeOf(Widget child) {
        return child.naturalSize();
    }

    /** Places a child. For {@link #arrange}; drawn and hit in the order placed. */
    protected final void place(Widget child, Bounds at) {
        child.layout(at);
        placing.add(child);
    }

    // --------------------------------------------------------------- engine

    /**
     * This frame's description, with a slot at its end if the renderer described
     * none &mdash; or if there was no renderer, or it threw, which leaves a bare
     * slot.
     */
    private Content describeWithSlot() {
        Content described = describe();
        if (!described.hasSlot()) {
            described.slot();
        }
        return described;
    }

    @Override
    float measure(float width) {
        Content described = describeWithSlot();
        described.width(width);
        float slotWidth = slotWidth(described, width);
        described.fillSlot(slotWidth, childrenHeight(visible(), slotWidth));
        return described.measure().getHeight();
    }

    @Override
    Size measureNatural() {
        Content described = describeWithSlot();
        described.width(0f);
        Size natural = childrenNatural(visible());
        described.fillSlot(natural.getWidth(), natural.getHeight());
        return described.measure();
    }

    @Override
    void layout(Bounds at) {
        Content described = describeWithSlot();
        described.width(at.getWidth());
        List<Widget> visible = visible();
        float slotWidth = slotWidth(described, at.getWidth());
        described.fillSlot(slotWidth, childrenHeight(visible, slotWidth));
        described.measure();
        described.layout(at.getX(), at.getY(), at.getWidth(), at.getHeight());
        placed(at, described);

        Bounds slot = described.slotBounds();
        placing.clear();
        arrange(visible, slot == null ? at : slot);
        placed = Collections.unmodifiableList(new ArrayList<>(placing));
    }

    /**
     * The first pass: the description laid out with its slot empty, to find how
     * wide the slot is at this width.
     */
    private static float slotWidth(Content described, float width) {
        described.fillSlot(0f, 0f);
        Size chrome = described.measure();
        described.layout(0f, 0f, width, chrome.getHeight());
        Bounds slot = described.slotBounds();
        return slot == null ? width : slot.getWidth();
    }

    @Override
    void draw() {
        super.draw();
        Bounds clip = clipChildren();
        if (clip != null) {
            Render.pushClip(clip.getX(), clip.getY(), clip.getWidth(), clip.getHeight());
        }
        try {
            for (Widget child : placed) {
                child.draw();
            }
        } finally {
            if (clip != null) {
                Render.popClip();
            }
        }
    }

    @Override
    Widget widgetAt(float x, float y) {
        if (!getShape().contains(x, y)) {
            return null;
        }
        Bounds clip = clipChildren();
        if (clip != null && !clip.contains(x, y)) {
            return this;
        }
        for (int i = placed.size() - 1; i >= 0; i--) {
            Widget found = placed.get(i).widgetAt(x, y);
            if (found != null) {
                return found;
            }
        }
        return this;
    }

    @Override
    void forget() {
        super.forget();
        placed = Collections.emptyList();
        for (Widget child : children) {
            child.forget();
        }
    }

    /** The children taking part now, read live: what measuring and layout go by. */
    private List<Widget> visible() {
        if (!showsChildren()) {
            return Collections.emptyList();
        }
        List<Widget> visible = new ArrayList<>(children.size());
        for (Widget child : children) {
            if (child.isVisible()) {
                visible.add(child);
            }
        }
        return visible;
    }
}
