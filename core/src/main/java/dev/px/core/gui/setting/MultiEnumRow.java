package dev.px.core.gui.setting;

import dev.px.core.layout.Align;

import dev.px.core.layout.Content;

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
        return GuiStyle.indent();
    }

    @Override
    protected void content(Content c) {
        header(c, open, row -> {
            value(row, getSetting().displayValue());
            caret(row, open);
        });
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
        protected void content(Content c) {
            boolean on = owner.has(option);
            c.height(GuiStyle.rowHeight()).align(Align.CENTER);
            c.row(r -> {
                r.gap(GuiStyle.padding()).align(Align.CENTER);
                r.roundRect(BOX, BOX, 1f, on ? GuiStyle.accent() : GuiStyle.outline());
                r.text(label, on ? GuiStyle.text() : GuiStyle.textMuted());
            });
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
