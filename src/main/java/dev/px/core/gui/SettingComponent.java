package dev.px.core.gui;

import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.Setting;
import dev.px.core.util.Validate;
import lombok.Getter;

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
 *   <li><b>The header</b> is one {@link GuiStyle#ROW_HEIGHT} row, so a row that
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
        setPadding(GuiStyle.SPACING);
    }

    @Override
    protected float headerHeight() {
        return GuiStyle.ROW_HEIGHT;
    }

    @Override
    public boolean isVisible() {
        return setting.isVisible();
    }

    @Override
    public String getTooltip() {
        return setting.getDescription();
    }

    // -------------------------------------------------------- drawing helpers

    /**
     * Fills the header row.
     *
     * @param highlighted whether to tint it, for a row that is open, focused or
     *                    under the cursor
     */
    protected void renderRow(float x, float y, float w, boolean highlighted) {
        float height = GuiStyle.ROW_HEIGHT;
        Color fill = highlighted
                ? GuiStyle.accent().withAlpha(70)
                : GuiStyle.surface();
        Render.roundRect(x, y, w, height, GuiStyle.radius(w, height), fill);
    }

    /** Draws the setting's name at the left of the header. */
    protected void renderLabel(float x, float y) {
        Render.text(setting.getName(), x + GuiStyle.PADDING, baseline(y), GuiStyle.text());
    }

    /** Draws a value flush with the right of the header. */
    protected void renderValue(String text, float x, float y, float w) {
        renderValue(text, x, y, w, GuiStyle.textMuted());
    }

    /**
     * Right-aligns by measuring rather than by handing {@link Render} a font and
     * an {@link dev.px.core.render.Alignment}.
     *
     * <p>That overload takes the font as an argument and measures with it, so it
     * needs a real one; the no-argument calls are the ones that go quiet when
     * none is installed. Measuring here keeps every draw on this row inside the
     * documented contract that drawing before a backend exists is harmless,
     * which is what lets a row lay itself out during early startup.
     */
    protected void renderValue(String text, float x, float y, float w, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float right = x + w - GuiStyle.PADDING - Render.textWidth(text);
        Render.text(text, right, baseline(y), color);
    }

    /**
     * @return the y a single line of text sits at to look centred in a row
     *
     * <p>With no font installed {@link Render#textHeight()} is 0 and this
     * degrades to the row's middle, which is harmless: nothing is drawn anyway.
     */
    protected static float baseline(float y) {
        return y + (GuiStyle.ROW_HEIGHT - Render.textHeight()) / 2f;
    }

    /**
     * Draws the caret that marks something expandable.
     *
     * <p>Pointing right when closed and down when open, which is the one
     * convention every user already knows.
     */
    protected void renderCaret(float x, float y, boolean open) {
        float size = 3f;
        float centerY = y + GuiStyle.ROW_HEIGHT / 2f;
        Color color = GuiStyle.textMuted();
        if (open) {
            Render.triangle(x - size, centerY - size / 2f, x + size, centerY - size / 2f,
                    x, centerY + size, color);
        } else {
            Render.triangle(x - size / 2f, centerY - size, x - size / 2f, centerY + size,
                    x + size, centerY, color);
        }
    }

    /**
     * @return how far along the row a horizontal position sits, 0..1
     *
     * <p>What every slider and every colour strip needs, and the one place the
     * padding on either side is accounted for.
     */
    protected static float progressAt(float pointerX, float x, float w) {
        float usable = w - GuiStyle.PADDING * 2f;
        if (usable <= 0f) {
            return 0f;
        }
        float progress = (pointerX - (x + GuiStyle.PADDING)) / usable;
        return progress < 0f ? 0f : progress > 1f ? 1f : progress;
    }
}
