package dev.px.gui.container;

import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Size;
import dev.px.core.util.Validate;
import dev.px.gui.Container;
import dev.px.gui.Widget;

import lombok.Getter;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Children on top of one another, each filling the stack, aligned within it,
 * or at an offset from its corner.
 *
 * <pre>{@code
 * Stack desktop = new Stack();
 * desktop.add(background);                               // fills the stack
 * desktop.add(menu, Align.CENTER, Align.CENTER);         // its own size, centred
 * desktop.add(window, 40f, 30f);                         // its own size, at (40, 30)
 * desktop.move(window, 120f, 30f);                       // dragged somewhere else
 * }</pre>
 *
 * <p>Later children are drawn over earlier ones and hit before them. An aligned
 * child is as large as it wants to be on each axis, but no larger than the
 * stack, and {@link Align#STRETCH} fills one axis alone. A child at an offset
 * keeps its own size wherever it is moved, even past the stack's edge, as a
 * dragged window should. The stack is as large as the largest child, offsets
 * included.
 *
 * <p>Game thread only.
 */
public class Stack extends Container {

    /** Where a child goes. Children with none fill the stack. */
    private static final class Position {

        static final Position FILL = new Position(Align.STRETCH, Align.STRETCH, 0f, 0f, false);

        final Align across;
        final Align down;
        final float x;
        final float y;

        /** Placed at an offset, at its own size, rather than aligned. */
        final boolean offset;

        Position(Align across, Align down, float x, float y, boolean offset) {
            this.across = across;
            this.down = down;
            this.x = x;
            this.y = y;
            this.offset = offset;
        }
    }

    private final Map<Widget, Position> positions = new IdentityHashMap<>();

    /** Where the children were arranged at the last update. Empty before the first. */
    @Getter
    private Bounds slot = Bounds.EMPTY;

    /** Adds a child that fills the stack. */
    @Override
    public <W extends Widget> W add(W child) {
        W added = super.add(child);
        positions.remove(added);
        return added;
    }

    /** Adds a child at its own size, aligned within the stack; {@link Align#STRETCH} fills that axis. */
    public <W extends Widget> W add(W child, Align across, Align down) {
        W added = super.add(child);
        align(added, across, down);
        return added;
    }

    /** Adds a child at its own size, at an offset from the stack's top-left corner. */
    public <W extends Widget> W add(W child, float x, float y) {
        W added = super.add(child);
        move(added, x, y);
        return added;
    }

    /** Aligns a child within the stack, at its own size. */
    public void align(Widget child, Align across, Align down) {
        Validate.check(getChildren().contains(child), "only a child of this stack can be aligned in it");
        positions.put(child, new Position(Validate.notNull(across, "across"), Validate.notNull(down, "down"),
                0f, 0f, false));
    }

    /** Puts a child at an offset from the stack's top-left corner, at its own size. */
    public void move(Widget child, float x, float y) {
        Validate.check(getChildren().contains(child), "only a child of this stack can be moved in it");
        positions.put(child, new Position(Align.START, Align.START, x, y, true));
    }

    @Override
    public void remove(Widget child) {
        super.remove(child);
        positions.remove(child);
    }

    @Override
    public Stack visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Stack enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Stack grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Stack tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Stack tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }

    // ------------------------------------------------------------ arranging

    @Override
    protected float childrenHeight(List<Widget> visible, float width) {
        float height = 0f;
        for (Widget child : visible) {
            Position at = positionOf(child);
            height = Math.max(height, at.y + heightOf(child, widthOf(child, at, width)));
        }
        return height;
    }

    @Override
    protected Size childrenNatural(List<Widget> visible) {
        float width = 0f;
        float height = 0f;
        for (Widget child : visible) {
            Position at = positionOf(child);
            float natural = naturalSizeOf(child).getWidth();
            width = Math.max(width, at.x + natural);
            height = Math.max(height, at.y + heightOf(child, natural));
        }
        return Size.of(width, height);
    }

    @Override
    protected void arrange(List<Widget> visible, Bounds slot) {
        this.slot = slot;
        for (Widget child : visible) {
            Position at = positionOf(child);
            float width = widthOf(child, at, slot.getWidth());
            float height = at.down == Align.STRETCH ? slot.getHeight() : heightOf(child, width);
            if (!at.offset) {
                height = Math.min(height, slot.getHeight());
            }
            float x = slot.getX() + at.x + Flex.offset(at.across, slot.getWidth(), width);
            float y = slot.getY() + at.y + Flex.offset(at.down, slot.getHeight(), height);
            place(child, Bounds.of(x, y, width, height));
        }
    }

    private Position positionOf(Widget child) {
        Position at = positions.get(child);
        return at == null ? Position.FILL : at;
    }

    /** The stack's width when filling across, otherwise the child's own, capped to the stack when aligned. */
    private float widthOf(Widget child, Position at, float width) {
        if (at.across == Align.STRETCH) {
            return width;
        }
        float natural = naturalSizeOf(child).getWidth();
        return at.offset ? natural : Math.min(natural, width);
    }
}
