package dev.px.core.gui.setting;

import dev.px.core.layout.Align;

import dev.px.core.layout.Content;

import dev.px.core.gui.Component;
import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.MouseButton;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.EnumSetting;

/**
 * A dropdown.
 *
 * <p>The options are real child components rather than a popup drawn over
 * everything else, which is what lets hit testing stay one walk down the tree:
 * an open dropdown is simply taller, and the rows below it move down. A floating
 * overlay would need its own z-order and its own hit pass, for a click GUI that
 * has room to expand in place.
 */
public final class EnumRow extends SettingComponent<EnumSetting<?>> {

    private boolean open;

    public EnumRow(EnumSetting<?> setting) {
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

    /** The wheel cycles without opening the list, for changing a mode in passing. */
    @Override
    protected boolean onScroll(float amount, float x, float y) {
        if (amount > 0f) {
            getSetting().cycleBack();
        } else {
            getSetting().cycle();
        }
        return true;
    }

    /**
     * Applies an option.
     *
     * <p>Raw because the row holds an {@code EnumSetting<?>}: the wildcard has no
     * name to write the cast against, and the option came out of this very
     * setting's own option list, so the type is right by construction.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void select(Enum<?> option) {
        ((EnumSetting) getSetting()).set(option);
        open = false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    String labelOf(Enum<?> option) {
        return ((EnumSetting) getSetting()).labelOf((Enum) option);
    }

    boolean isSelected(Enum<?> option) {
        return getSetting().get() == option;
    }

    /** One choice in the open list. */
    private static final class OptionRow extends Component {

        private final EnumRow owner;
        private final Enum<?> option;

        OptionRow(EnumRow owner, Enum<?> option) {
            this.owner = owner;
            this.option = option;
        }

        @Override
        protected void content(Content c) {
            boolean selected = owner.isSelected(option);
            float height = GuiStyle.rowHeight();
            c.height(height).padding(GuiStyle.padding(), 0f).align(Align.CENTER);
            if (selected) {
                c.background(GuiStyle.accent().withAlpha(90), Math.min(GuiStyle.radius(), height / 2f));
            }
            c.text(owner.labelOf(option), selected ? GuiStyle.text() : GuiStyle.textMuted());
        }

        @Override
        protected boolean onClick(float x, float y, MouseButton button) {
            if (button != MouseButton.LEFT) {
                return false;
            }
            owner.select(option);
            return true;
        }
    }
}
