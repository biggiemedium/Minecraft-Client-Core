package dev.px.core.gui;

import dev.px.core.hud.Bounds;
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

    private float padding = GuiStyle.PADDING;
    private float spacing = GuiStyle.SPACING;

    /** Whether to fill the panel's own rectangle before the children are drawn. */
    private boolean background;

    // ------------------------------------------------------------- overrides

    /**
     * @return space reserved above the children for this component's own drawing
     *
     * <p>Zero by default: a plain panel is nothing but its children.
     */
    protected float headerHeight() {
        return 0f;
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
        float header = headerHeight();
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

    @Override
    public void render(float x, float y, float w, float h) {
        if (background) {
            Render.roundRect(x, y, w, h, GuiStyle.radius(w, h), GuiStyle.background());
        }
    }
}
