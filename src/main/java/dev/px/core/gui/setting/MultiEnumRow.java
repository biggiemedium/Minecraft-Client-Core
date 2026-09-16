package dev.px.core.gui.setting;

import dev.px.core.gui.Component;
import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.MouseButton;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.MultiEnumSetting;
import dev.px.core.util.text.TextUtil;

/**
 * A checkbox list: several options from one enum, on at once.
 *
 * <p>Unlike a dropdown this stays open after a click, because selecting one
 * option is rarely the whole intent &mdash; a target filter is usually edited
 * two or three checkboxes at a time.
 */
public final class MultiEnumRow extends SettingComponent<MultiEnumSetting<?>> {

    private static final float BOX = 5f;

    private boolean open;

    public MultiEnumRow(MultiEnumSetting<?> setting) {
        super(setting);
        for (Enum<?> option : setting.getOptions()) {
            add(new OptionRow(this, option));
        }
    }

    @Override
    protected boolean showsChildren() {
        return open;
    }

    @Override
    protected float childIndent() {
        return GuiStyle.INDENT;
    }

    @Override
    public void render(float x, float y, float w, float h) {
        renderRow(x, y, w, open);
        renderLabel(x, y);
        renderValue(getSetting().displayValue(), x, y, w - 8f);
        renderCaret(x + w - GuiStyle.PADDING - 3f, y, open);
    }

    @Override
    protected boolean onClick(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        open = !open;
        return true;
    }

    /** Raw for the same reason {@link EnumRow#select} is: the row holds a wildcard. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void toggle(Enum<?> option) {
        ((MultiEnumSetting) getSetting()).toggle((Enum) option);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean has(Enum<?> option) {
        return ((MultiEnumSetting) getSetting()).has((Enum) option);
    }

    /** One checkbox in the open list. */
    private static final class OptionRow extends Component {

        private final MultiEnumRow owner;
        private final Enum<?> option;
        private final String label;

        OptionRow(MultiEnumRow owner, Enum<?> option) {
            this.owner = owner;
            this.option = option;
            this.label = TextUtil.prettify(option);
        }

        @Override
        public float getPreferredHeight(float width) {
            return GuiStyle.ROW_HEIGHT;
        }

        @Override
        public void render(float x, float y, float w, float h) {
            boolean on = owner.has(option);
            float boxY = y + (h - BOX) / 2f;
            Render.roundRect(x, boxY, BOX, BOX, 1f, on ? GuiStyle.accent() : GuiStyle.outline());
            Render.text(label, x + BOX + GuiStyle.PADDING, y + (h - Render.textHeight()) / 2f,
                    on ? GuiStyle.text() : GuiStyle.textMuted());
        }

        @Override
        protected boolean onClick(float x, float y, MouseButton button) {
            if (button != MouseButton.LEFT) {
                return false;
            }
            owner.toggle(option);
            return true;
        }
    }
}
