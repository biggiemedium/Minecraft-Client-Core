package dev.px.core.gui;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.render.Render;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * A component that stacks its children vertically.
 *
 * <p>This is the entire layout engine. There is no flexbox, no grid and no
 * constraint solver, because a click GUI is a column of rows inside a column of
 * windows and anything more general would be a framework nobody asked for. A
 * component that needs children and nothing clever extends this; one that needs
 * a genuinely different arrangement overrides {@link #layout} and owns the
 * consequences.
 *
 * <p>Three knobs shape the stack:
 *
 * <ul>
 *   <li>{@link #headerHeight()} reserves space at the top for the component's own
 *       drawing &mdash; a window's title bar, a group's header row. Children
 *       start below it.</li>
 *   <li>{@link #getPadding()} insets the children from all four edges.</li>
 *   <li>{@link #childIndent()} shifts children right, which is how a nested row
 *       reads as nested.</li>
 * </ul>
 *
 * <p>A panel whose visible children are all gone collapses to its header, so
 * hiding the last row of a group leaves the header rather than an empty box.
 */
@Getter
@Setter
public class Panel extends Component {

    private float padding = GuiStyle.padding();
    private float spacing = GuiStyle.spacing();

    /** Whether to fill the panel's own rectangle before the children are drawn. */
    private boolean background;

    // ------------------------------------------------------------- overrides

    /**
     * @return space reserved above the children for this component's own drawing
     *
     * <p>Measured from {@link #content}, not declared. A panel that describes a
     * row-high header reserves exactly that, and a panel that describes nothing
     * reserves nothing &mdash; so a header can never be a different height from
     * the thing drawn in it.
     */
    protected float headerHeight(float width) {
        return describe(width).size().getHeight();
    }

    /** @return how far to shift children right of the panel's content edge. */
    protected float childIndent() {
        return 0f;
    }

    // ---------------------------------------------------------------- layout

    @Override
    public float getPreferredHeight(float width) {
        return stack(0f, 0f, width, false);
    }

    @Override
    public void layout(float x, float y, float width) {
        // The header is this panel's own content, placed at the panel's corner;
        // the children stack underneath whatever it measured.
        Content header = describe(width);
        float headerHeight = header.size().getHeight();
        placeContent(header, x, y, width, headerHeight);

        setBounds(Bounds.of(x, y, width, stack(x, y, width, true)));
    }

    /**
     * Walks the children once, either measuring them or placing them.
     *
     * <p>One method rather than two so measurement and placement cannot drift
     * apart &mdash; a panel that measures itself taller than it lays out leaves a
     * dead strip that swallows clicks, and the bug is invisible until someone
     * clicks the gap.
     *
     * @return the total height the stack occupies
     */
    private float stack(float x, float y, float width, boolean place) {
        float header = headerHeight(width);
        List<Component> visible = visibleChildren();
        if (visible.isEmpty()) {
            return header > 0f ? header : padding * 2f;
        }

        float indent = childIndent();
        float inner = Math.max(0f, width - padding * 2f - indent);
        float cursor = y + header + padding;

        boolean first = true;
        for (Component child : visible) {
            if (!first) {
                cursor += spacing;
            }
            first = false;
            if (place) {
                child.layout(x + padding + indent, cursor, inner);
                cursor += child.getBounds().getHeight();
            } else {
                cursor += child.getPreferredHeight(inner);
            }
        }
        return cursor - y + padding;
    }

    // --------------------------------------------------------------- drawing

    /**
     * Fills the whole panel, children included.
     *
     * <p>A backdrop rather than content, because it has to span a height that is
     * only known once the children are placed &mdash; which is after content has
     * been measured.
     */
    @Override
    protected void renderBackdrop(float x, float y, float w, float h) {
        if (background) {
            Render.roundRect(x, y, w, h, GuiStyle.radius(w, h), GuiStyle.background());
        }
    }
}
