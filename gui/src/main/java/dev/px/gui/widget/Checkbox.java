package dev.px.gui.widget;

import dev.px.core.input.MouseButton;
import dev.px.core.util.Validate;
import dev.px.gui.Widget;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Something on or off.
 *
 * <pre>{@code
 * Checkbox sprint = panel.add(new Checkbox("Auto sprint", true).onChange(on -> config.sprint = on));
 *
 * look.register(WidgetRenderer.of(Checkbox.class, (box, c) -> c.row(r -> {
 *     r.gap(4f).align(Align.CENTER);
 *     r.roundRect(8f, 8f, 2f, box.isChecked() ? ACCENT : BASE);
 *     r.text(box.getLabel(), TEXT);
 * })));
 * }</pre>
 *
 * <p>A left press toggles it, and so does {@link dev.px.gui.Screen#activate()}
 * while it has focus. Listeners hear the new state whenever it changes, however
 * it was changed.
 *
 * <p>Game thread only.
 */
public class Checkbox extends Widget {

    @Getter
    private String label;

    @Getter
    private boolean checked;

    private final List<Consumer<Boolean>> listeners = new ArrayList<>(1);

    public Checkbox(String label, boolean checked) {
        setLabel(label);
        this.checked = checked;
    }

    public Checkbox setLabel(String value) {
        this.label = value == null ? "" : value;
        return this;
    }

    /** Runs {@code listener} with the new state each time it changes. */
    public Checkbox onChange(Consumer<Boolean> listener) {
        listeners.add(Validate.notNull(listener, "listener"));
        return this;
    }

    /** Sets the state, telling the listeners if it changed. */
    public void setChecked(boolean value) {
        if (checked == value) {
            return;
        }
        checked = value;
        for (Consumer<Boolean> listener : new ArrayList<>(listeners)) {
            listener.accept(value);
        }
    }

    /** Flips the state, unless the checkbox is disabled. @return whether it flipped */
    public boolean toggle() {
        if (!isEnabled()) {
            return false;
        }
        setChecked(!checked);
        return true;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected boolean mousePressed(float x, float y, MouseButton button) {
        return button == MouseButton.LEFT && toggle();
    }

    @Override
    protected boolean activated() {
        return toggle();
    }

    // --------------------------------------------------------------- chaining

    @Override
    public Checkbox visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Checkbox enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Checkbox grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Checkbox tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Checkbox tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
