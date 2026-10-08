package dev.px.gui.widget;

import dev.px.core.input.MouseButton;
import dev.px.gui.Widget;
import lombok.Getter;

/**
 * One choice in an open {@link DropdownMenu}.
 *
 * <pre>{@code
 * look.register(WidgetRenderer.of(DropdownItem.class, (item, c) -> c
 *         .background(item.isHovered() ? HOVER : item.isSelected() ? CHOSEN : NONE)
 *         .padding(4f)
 *         .text(item.getLabel(), TEXT)));
 * }</pre>
 *
 * <p>A left press, or {@link dev.px.gui.Screen#activate()} while it has focus,
 * chooses it and closes the menu. Made by the dropdown; a client never builds one.
 */
public final class DropdownItem extends Widget {

    private final Dropdown<?> dropdown;

    /** The choice this item stands for. */
    @Getter
    private final Object choice;

    <T> DropdownItem(Dropdown<T> dropdown, T choice) {
        this.dropdown = dropdown;
        this.choice = choice;
    }

    /** @return the choice's label, as the dropdown shows it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public String getLabel() {
        return ((Dropdown) dropdown).labelOf(choice);
    }

    /** @return whether this is the dropdown's current choice. */
    public boolean isSelected() {
        Object selected = dropdown.getSelected();
        return selected == choice || (selected != null && selected.equals(choice));
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected boolean mousePressed(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        dropdown.picked(choice);
        return true;
    }

    @Override
    protected boolean activated() {
        dropdown.picked(choice);
        return true;
    }
}
