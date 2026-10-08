package dev.px.gui.widget;

import dev.px.core.util.Validate;
import dev.px.gui.Widget;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Text, fixed or read live.
 *
 * <pre>{@code
 * column.add(new Label("My Client"));
 * column.add(new Label(() -> "Ping " + ping.get() + "ms"));
 * }</pre>
 *
 * <p>Holds the text and nothing else. Its font, colour and size are its
 * renderer's: {@code c.text(label.getText(), WHITE)}.
 *
 * <p>Game thread only.
 */
public class Label extends Widget {

    private Supplier<String> text;

    public Label(String text) {
        setText(text);
    }

    /** Text read each time it is asked for, so it can change without being set. */
    public Label(Supplier<String> text) {
        setText(text);
    }

    /** @return the text, never null. */
    public String getText() {
        String value = text.get();
        return value == null ? "" : value;
    }

    public Label setText(String value) {
        String fixed = value == null ? "" : value;
        this.text = () -> fixed;
        return this;
    }

    public Label setText(Supplier<String> source) {
        this.text = Validate.notNull(source, "source");
        return this;
    }

    @Override
    public Label visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Label enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Label grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Label tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Label tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
