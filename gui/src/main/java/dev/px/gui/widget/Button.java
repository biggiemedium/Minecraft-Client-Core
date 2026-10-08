package dev.px.gui.widget;

import dev.px.core.input.MouseButton;
import dev.px.core.util.Validate;
import dev.px.gui.Widget;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Something pressed to make something happen.
 *
 * <pre>{@code
 * Button play = menu.add(new Button("Singleplayer").onPress(adapter::singleplayer));
 * }</pre>
 *
 * <p>A left press on it presses it, and so does {@link dev.px.gui.Screen#activate()}
 * while it has focus; {@link #press()} is the same action for the client to
 * call. The listeners run in the order they were added. While the button is
 * held, {@link #isPressed()} is true. How it looks, hovered, held, enabled or
 * not, is its renderer's.
 *
 * <p>Game thread only.
 */
public class Button extends Widget {

    @Getter
    private String label;

    private final List<Runnable> listeners = new ArrayList<>(1);

    public Button(String label) {
        setLabel(label);
    }

    public Button setLabel(String value) {
        this.label = value == null ? "" : value;
        return this;
    }

    /** Runs {@code listener} each time the button is pressed. */
    public Button onPress(Runnable listener) {
        listeners.add(Validate.notNull(listener, "listener"));
        return this;
    }

    /**
     * Presses the button: runs its listeners, unless it is disabled.
     *
     * @return whether it was pressed
     */
    public boolean press() {
        if (!isEnabled()) {
            return false;
        }
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
        return true;
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
        capture();
        press();
        return true;
    }

    @Override
    protected boolean activated() {
        return press();
    }

    @Override
    public Button visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Button enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Button grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Button tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Button tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
