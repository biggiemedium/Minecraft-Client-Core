package dev.px.gui.widget;

import dev.px.core.input.MouseButton;
import dev.px.core.util.Validate;
import dev.px.gui.Popup;
import dev.px.gui.Screen;
import dev.px.gui.Widget;
import dev.px.gui.container.Scroll;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One choice from a list, picked from a popup that opens below it.
 *
 * <pre>{@code
 * Dropdown<Mode> mode = panel.add(new Dropdown<>(Arrays.asList(Mode.values()), Mode.SMOOTH))
 *         .labels(m -> m.name().toLowerCase())
 *         .onChange(chosen -> config.mode = chosen);
 *
 * look.register(WidgetRenderer.of(Dropdown.class, (dropdown, c) -> c.row(r -> {
 *     r.background(BASE, 3f).padding(4f).align(Align.CENTER);
 *     r.text(dropdown.getLabel(), TEXT).fill().text(dropdown.isOpen() ? "^" : "v", MUTED);
 * })));
 * look.register(WidgetRenderer.of(DropdownMenu.class, (menu, c) -> c.background(MENU, 3f).padding(2f).slot()));
 * look.register(WidgetRenderer.of(DropdownItem.class, (item, c) ->
 *         c.background(item.isHovered() ? HOVER : NONE).padding(4f).text(item.getLabel(), TEXT)));
 * }</pre>
 *
 * <p>A left press, or {@link Screen#activate()} while it has focus, opens a
 * {@link DropdownMenu} below it, as wide as it is, with a {@link DropdownItem}
 * per choice inside a {@code Scroll}; pressing again closes it. Picking an item
 * chooses it and closes the menu, and so does pressing or activating one. The
 * menu closes like any popup: a press outside it, {@link Screen#cancel()}, or the
 * dropdown leaving the screen. A long list is cut to the room on screen and
 * scrolls, or to {@link #listHeight} if set.
 *
 * <p>Game thread only.
 *
 * @param <T> what is chosen
 */
public class Dropdown<T> extends Widget {

    private final List<T> choices;

    @Getter
    private T selected;

    private Function<T, String> labels = String::valueOf;

    private float listHeight = Float.POSITIVE_INFINITY;

    private final List<Consumer<T>> listeners = new ArrayList<>(1);

    /** The open menu, or null. */
    private DropdownMenu menu;

    /**
     * @param selected the choice to start on, or null for none
     * @throws IllegalArgumentException if {@code selected} is not one of the choices
     */
    public Dropdown(List<T> choices, T selected) {
        this.choices = new ArrayList<>(Validate.notNull(choices, "choices"));
        Validate.check(selected == null || this.choices.contains(selected),
                "a dropdown's selected value must be one of its choices");
        this.selected = selected;
    }

    /** How each choice is shown. {@code String.valueOf} by default. */
    public Dropdown<T> labels(Function<T, String> naming) {
        this.labels = Validate.notNull(naming, "naming");
        return this;
    }

    /** Caps the open list's height; it scrolls beyond it. As tall as there is room for by default. */
    public Dropdown<T> listHeight(float height) {
        Validate.check(height >= 0f, "a dropdown's list height cannot be negative");
        this.listHeight = height;
        return this;
    }

    /** Runs {@code listener} with the new choice each time it changes. */
    public Dropdown<T> onChange(Consumer<T> listener) {
        listeners.add(Validate.notNull(listener, "listener"));
        return this;
    }

    public List<T> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    /** @return the selected choice's label, empty with none selected. */
    public String getLabel() {
        return selected == null ? "" : labelOf(selected);
    }

    /** @return how a choice is shown. */
    public String labelOf(T choice) {
        String label = labels.apply(choice);
        return label == null ? "" : label;
    }

    /** Chooses one of the choices, telling the listeners if it changed. */
    public void select(T choice) {
        Validate.check(choice == null || choices.contains(choice), "only one of a dropdown's choices can be selected");
        if (choice == selected || (choice != null && choice.equals(selected))) {
            return;
        }
        selected = choice;
        for (Consumer<T> listener : new ArrayList<>(listeners)) {
            listener.accept(choice);
        }
    }

    public boolean isOpen() {
        return menu != null;
    }

    /** Opens the menu below the dropdown, unless it is open, disabled, empty or not on a screen. */
    public void open() {
        Screen screen = getScreen();
        if (menu != null || screen == null || !isEnabled() || choices.isEmpty()) {
            return;
        }
        DropdownMenu opening = new DropdownMenu();
        Scroll list = opening.add(new Scroll().maxHeight(listHeight)).grow(1f);
        for (T choice : choices) {
            list.add(new DropdownItem(this, choice));
        }
        menu = screen.popup(opening, Popup.below(this).matchWidth().onClose(() -> menu = null));
    }

    public void close() {
        Screen screen = getScreen();
        if (menu != null && screen != null) {
            screen.close(menu);
        }
    }

    /** What an item does when pressed: choose it and close. */
    @SuppressWarnings("unchecked")
    void picked(Object choice) {
        select((T) choice);
        close();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected boolean mousePressed(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        toggle();
        return true;
    }

    @Override
    protected boolean activated() {
        toggle();
        return true;
    }

    private void toggle() {
        if (isOpen()) {
            close();
        } else {
            open();
        }
    }

    // --------------------------------------------------------------- chaining

    @Override
    public Dropdown<T> visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Dropdown<T> enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Dropdown<T> grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Dropdown<T> tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Dropdown<T> tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
