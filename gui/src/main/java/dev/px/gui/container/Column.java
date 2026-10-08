package dev.px.gui.container;

import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Size;
import dev.px.core.util.Validate;
import dev.px.gui.Container;
import dev.px.gui.Widget;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Children stacked top to bottom.
 *
 * <pre>{@code
 * Column menu = new Column().gap(4f);
 * menu.add(new Label("My Client"));
 * menu.add(new Button("Singleplayer"));
 * menu.add(list).grow(1f);              // the rest of the height
 * }</pre>
 *
 * <p>Each child is as tall as it measures, plus its share of any room left over
 * if it {@linkplain Widget#grow grows}. Across, children span the column by
 * default; {@link #align} sets them at their own width instead, at the start,
 * centre or end.
 *
 * <p>The space between children is the column's own, set where the screen is
 * built; it is zero unless set, which is no look at all. Padding and any
 * background are the renderer's, around its {@code slot()}.
 *
 * <p>Game thread only.
 */
public class Column extends Container {

    /** Space between neighbouring children. */
    @Getter
    private float gap;

    /** Where children sit across the column. {@link Align#STRETCH} spans it. */
    @Getter
    private Align align = Align.STRETCH;

    /** Sets the space between neighbouring children. */
    public Column gap(float spacing) {
        Validate.check(spacing >= 0f, "a column's gap cannot be negative");
        this.gap = spacing;
        return this;
    }

    /** Sets where children sit across the column. */
    public Column align(Align across) {
        this.align = Validate.notNull(across, "across");
        return this;
    }

    @Override
    public Column visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Column enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Column grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Column tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Column tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }

    // ------------------------------------------------------------ arranging

    @Override
    protected float childrenHeight(List<Widget> visible, float width) {
        float height = 0f;
        for (Widget child : visible) {
            height += heightOf(child, widthOf(child, width));
        }
        return height + Flex.gaps(visible.size(), gap);
    }

    /** As wide as its widest child, with every child as tall as it is at that width. */
    @Override
    protected Size childrenNatural(List<Widget> visible) {
        float width = 0f;
        for (Widget child : visible) {
            width = Math.max(width, naturalSizeOf(child).getWidth());
        }
        return Size.of(width, childrenHeight(visible, width));
    }

    @Override
    protected void arrange(List<Widget> visible, Bounds slot) {
        List<Bounds> places = stack(visible, slot);
        for (int i = 0; i < visible.size(); i++) {
            place(visible.get(i), places.get(i));
        }
    }

    /**
     * @return where each child goes when the column's children fill
     *         {@code area}, without placing them
     *
     * <p>For a subclass that places them differently, as a scrolling list does.
     */
    protected final List<Bounds> stack(List<Widget> visible, Bounds area) {
        float[] widths = new float[visible.size()];
        float[] heights = new float[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            widths[i] = widthOf(visible.get(i), area.getWidth());
            heights[i] = heightOf(visible.get(i), widths[i]);
        }
        float[] shared = Flex.share(visible, heights, area.getHeight() - Flex.gaps(visible.size(), gap));

        List<Bounds> places = new ArrayList<>(visible.size());
        float y = area.getY();
        for (int i = 0; i < visible.size(); i++) {
            float x = area.getX() + Flex.offset(align, area.getWidth(), widths[i]);
            places.add(Bounds.of(x, y, widths[i], shared[i]));
            y += shared[i] + gap;
        }
        return places;
    }

    /** A child's width: the column's when stretching, otherwise its own, no wider than the column. */
    private float widthOf(Widget child, float width) {
        return align == Align.STRETCH ? width : Math.min(width, naturalSizeOf(child).getWidth());
    }
}
