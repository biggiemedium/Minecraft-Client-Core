package dev.px.gui.widget;

import dev.px.gui.container.Column;

/**
 * The popup a {@link Dropdown} opens: a column holding a scrolling list of
 * {@link DropdownItem}s.
 *
 * <p>Its own type so the client can give it a look of its own &mdash; a
 * background and a border around the list:
 *
 * <pre>{@code
 * look.register(WidgetRenderer.of(DropdownMenu.class, (menu, c) -> c.background(MENU, 3f).padding(2f).slot()));
 * }</pre>
 *
 * <p>Made and opened by the dropdown; a client never builds one.
 */
public final class DropdownMenu extends Column {

    DropdownMenu() {
    }
}
