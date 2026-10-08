package dev.px.gui.container;

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
 * Children in cells of equal width, filled left to right and then down.
 *
 * <pre>{@code
 * Grid swatches = new Grid(4).gap(2f);
 * for (Color colour : palette) {
 *     swatches.add(new Swatch(colour));
 * }
 * }</pre>
 *
 * <p>Every cell in a row is as tall as the tallest child in it, and each child
 * fills its cell. The gaps between columns and between rows are the grid's
 * own, zero unless set.
 *
 * <p>Game thread only.
 */
public class Grid extends Container {

    @Getter
    private final int columns;

    @Getter
    private float columnGap;

    @Getter
    private float rowGap;

    /** @throws IllegalArgumentException if {@code columns} is less than one */
    public Grid(int columns) {
        Validate.check(columns >= 1, "a grid needs at least one column");
        this.columns = columns;
    }

    /** Sets the same space between columns and between rows. */
    public Grid gap(float spacing) {
        return gap(spacing, spacing);
    }

    public Grid gap(float betweenColumns, float betweenRows) {
        Validate.check(betweenColumns >= 0f && betweenRows >= 0f, "a grid's gaps cannot be negative");
        this.columnGap = betweenColumns;
        this.rowGap = betweenRows;
        return this;
    }

    @Override
    public Grid visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Grid enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Grid grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Grid tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Grid tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }

    // ------------------------------------------------------------ arranging

    @Override
    protected float childrenHeight(List<Widget> visible, float width) {
        float cell = cellWidth(width);
        float height = 0f;
        for (float row : rowHeights(visible, cell)) {
            height += row;
        }
        return height + Flex.gaps(rows(visible.size()), rowGap);
    }

    /** Every column as wide as the widest child wants. */
    @Override
    protected Size childrenNatural(List<Widget> visible) {
        float cell = 0f;
        for (Widget child : visible) {
            cell = Math.max(cell, naturalSizeOf(child).getWidth());
        }
        float width = cell * columns + Flex.gaps(columns, columnGap);
        return Size.of(width, childrenHeight(visible, width));
    }

    @Override
    protected void arrange(List<Widget> visible, Bounds slot) {
        float cell = cellWidth(slot.getWidth());
        float[] rows = rowHeights(visible, cell);
        float y = slot.getY();
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= visible.size()) {
                    break;
                }
                float x = slot.getX() + column * (cell + columnGap);
                place(visible.get(index), Bounds.of(x, y, cell, rows[row]));
            }
            y += rows[row] + rowGap;
        }
    }

    private float cellWidth(float width) {
        return Math.max(0f, (width - Flex.gaps(columns, columnGap)) / columns);
    }

    private int rows(int count) {
        return (count + columns - 1) / columns;
    }

    /** Each row's height: its tallest child at the cell width. */
    private float[] rowHeights(List<Widget> visible, float cell) {
        float[] rows = new float[rows(visible.size())];
        for (int i = 0; i < visible.size(); i++) {
            int row = i / columns;
            rows[row] = Math.max(rows[row], heightOf(visible.get(i), cell));
        }
        return rows;
    }
}
