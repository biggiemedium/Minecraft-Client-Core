package dev.px.core.gui;

import dev.px.core.layout.Draw;

import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.Setting;
import dev.px.core.util.Validate;
import lombok.Getter;

import java.util.function.Consumer;

/**
 * Base for the row that edits one setting.
 *
 * <p>Handles the three things every row does the same way, so that nine
 * renderers do not each get them slightly wrong:
 *
 * <ul>
 *   <li><b>Visibility</b> follows {@code visibleWhen}. A row whose setting is
 *       hidden takes part in nothing, so it cannot be clicked through the gap it
 *       left behind.</li>
 *   <li><b>The tooltip</b> is the setting's {@code describe} text, with no row
 *       having to remember to forward it.</li>
 *   <li><b>The header</b> is one {@link GuiStyle#rowHeight()} row, so a row that
 *       opens into a picker or a list lines up with the plain ones above it.</li>
 * </ul>
 *
 * <p>Extending {@link Panel} means a row that expands &mdash; a group, a
 * dropdown, a colour picker &mdash; gets its children stacked underneath the
 * header for free, and gates them with {@link #showsChildren()}.
 *
 * @param <S> the setting type this row edits
 */
public abstract class SettingComponent<S extends Setting<?>> extends Panel {

    @Getter
    private final S setting;

    protected SettingComponent(S setting) {
        this.setting = Validate.notNull(setting, "setting");
        setPadding(GuiStyle.spacing());
    }

    @Override
    public boolean isVisible() {
        return setting.isVisible();
    }

    @Override
    public String getTooltip() {
        return setting.getDescription();
    }

    // -------------------------------------------------------- content helpers

    /**
     * The standard header: the setting's name on the left, whatever you add on
     * the right.
     *
     * <p>Every row in the GUI is this shape, so it is described once here rather
     * than nine times with nine slightly different paddings. The header's height
     * is also what {@link Panel} reserves above the children, so a row that opens
     * into a picker lines up with the plain ones above it automatically.
     *
     * @param highlighted tints the row: open, focused, or capturing a key
     * @param right called with the header row, to add trailing parts
     */
    protected void header(Content content, boolean highlighted, Consumer<Content> right) {
        float height = GuiStyle.rowHeight();
        Color fill = highlighted ? GuiStyle.accent().withAlpha(70) : GuiStyle.surface();

        content.height(height)
                .align(Align.STRETCH)
                .background(fill, Math.min(GuiStyle.radius(), height / 2f));

        content.row(row -> {
            // Clipped, so a long value is cut off at the row's edge rather than
            // spilling out past the window it sits in.
            row.grow().clip()
                    .padding(GuiStyle.padding(), 0f).gap(GuiStyle.padding()).align(Align.CENTER);
            row.text(setting.getName(), GuiStyle.text());
            row.fill();
            if (right != null) {
                right.accept(row);
            }
        });
    }

    /** The header with nothing after the label. */
    protected void header(Content content, boolean highlighted) {
        header(content, highlighted, null);
    }

    /** Adds the setting's value, dimmed, at the right of a header row. */
    protected void value(Content row, String text) {
        value(row, text, GuiStyle.textMuted());
    }

    protected void value(Content row, String text, Color color) {
        if (text != null && !text.isEmpty()) {
            row.text(text, color);
        }
    }

    /**
     * Adds the caret that marks something expandable.
     *
     * <p>Pointing right when closed and down when open, which is the one
     * convention every user already knows.
     */
    protected void caret(Content row, boolean open) {
        float size = 3f;
        row.space(GuiStyle.padding(), 0f);
        row.custom("caret", size * 2f, size * 2f, (x, y, w, h) -> {
            float centerX = x + w / 2f;
            float centerY = y + h / 2f;
            Color color = GuiStyle.textMuted();
            if (open) {
                Render.triangle(centerX - size, centerY - size / 2f,
                        centerX + size, centerY - size / 2f, centerX, centerY + size, color);
            } else {
                Render.triangle(centerX - size / 2f, centerY - size,
                        centerX - size / 2f, centerY + size, centerX + size, centerY, color);
            }
        });
    }

    /**
     * Adds a full-width track along the bottom of the row, named so a click can
     * be measured against it.
     *
     * <p>Needs the row to be {@code align(Align.STRETCH)} with the label row
     * marked {@code grow()}, which {@link #header} already does.
     */
    protected void track(Content content, float height, dev.px.core.layout.Draw draw) {
        content.custom("track", 0f, height, draw);
        content.space(0f, 1f);
    }

    /**
     * @return how far along a named part a horizontal position sits, 0..1
     *
     * <p>Measured against the rectangle the part was actually drawn in, so the
     * region that responds and the region that was drawn cannot disagree. That
     * was the whole reason for naming it.
     */
    protected float progressIn(String partName, float pointerX) {
        Bounds at = part(partName);
        if (at == null || at.getWidth() <= 0f) {
            return 0f;
        }
        float progress = (pointerX - at.getX()) / at.getWidth();
        return progress < 0f ? 0f : progress > 1f ? 1f : progress;
    }
}
