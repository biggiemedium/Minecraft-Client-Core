package dev.px.gui.container;

import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Size;
import dev.px.core.util.Validate;
import dev.px.gui.Container;
import dev.px.gui.Widget;
import lombok.Getter;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Children side by side, left to right.
 *
 * <pre>{@code
 * Row bar = new Row().gap(4f).align(Align.CENTER);
 * bar.add(new Label("Search"));
 * bar.add(field).grow(1f);              // the rest of the width
 * bar.add(new Button("Go"));
 * }</pre>
 *
 * <p>Each child is as wide as it wants to be, plus its share of any room left
 * over if it {@linkplain Widget#grow grows}. Down, children span the row by
 * default; {@link #align} sets them at their own height instead, at the top,
 * middle or bottom.
 *
 * <p>The row is as tall as its tallest child at the width it gets. The space
 * between children is the row's own, zero unless set; padding and any
 * background are the renderer's, around its {@code slot()}.
 *
 * <p>Game thread only.
 */
public class Row extends Container {

    /** Space between neighbouring children. */
    @Getter
    private float gap;

    /** Where children sit down the row. {@link Align#STRETCH} spans it. */
    @Getter
    private Align align = Align.STRETCH;

    /** Sets the space between neighbouring children. */
    public Row gap(float spacing) {
        Validate.check(spacing >= 0f, "a row's gap cannot be negative");
        this.gap = spacing;
        return this;
    }

    /** Sets where children sit down the row. */
    public Row align(Align down) {
        this.align = Validate.notNull(down, "down");
        return this;
    }

    @Override
    public Row visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Row enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Row grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Row tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Row tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }

    // ------------------------------------------------------------ arranging

    @Override
    protected float childrenHeight(List<Widget> visible, float width) {
        float[] widths = widths(visible, width);
        float height = 0f;
        for (int i = 0; i < visible.size(); i++) {
            height = Math.max(height, heightOf(visible.get(i), widths[i]));
        }
        return height;
    }

    /** As wide as its children side by side, as tall as the tallest. */
    @Override
    protected Size childrenNatural(List<Widget> visible) {
        float width = Flex.gaps(visible.size(), gap);
        float height = 0f;
        for (Widget child : visible) {
            Size natural = naturalSizeOf(child);
            width += natural.getWidth();
            height = Math.max(height, heightOf(child, natural.getWidth()));
        }
        return Size.of(width, height);
    }

    @Override
    protected void arrange(List<Widget> visible, Bounds slot) {
        float[] widths = widths(visible, slot.getWidth());
        float x = slot.getX();
        for (int i = 0; i < visible.size(); i++) {
            Widget child = visible.get(i);
            float height = align == Align.STRETCH ? slot.getHeight() : heightOf(child, widths[i]);
            float y = slot.getY() + Flex.offset(align, slot.getHeight(), height);
            place(child, Bounds.of(x, y, widths[i], height));
            x += widths[i] + gap;
        }
    }

    /** Each child's width when the row is {@code width} wide. */
    private float[] widths(List<Widget> visible, float width) {
        float[] natural = new float[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            natural[i] = naturalSizeOf(visible.get(i)).getWidth();
        }
        return Flex.share(visible, natural, width - Flex.gaps(visible.size(), gap));
    }
}
