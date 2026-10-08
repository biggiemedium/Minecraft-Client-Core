package dev.px.gui.widget;

import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.layout.Bounds;
import dev.px.core.util.Validate;
import dev.px.gui.Widget;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * A number between two bounds, dragged along a track.
 *
 * <pre>{@code
 * Slider reach = panel.add(new Slider(3, 6, 4.5).step(0.1).onChange(value -> config.reach = value));
 *
 * look.register(WidgetRenderer.of(Slider.class, (slider, c) -> {
 *     c.align(Align.STRETCH).gap(2f);
 *     c.text(String.format("%.1f", slider.getValue()), TEXT);
 *     c.custom("track", 0f, 3f, (x, y, w, h) -> {
 *         Render.rect(x, y, w, h, TRACK);
 *         Render.rect(x, y, w * slider.getProgress(), h, ACCENT);
 *     });
 * }));
 * }</pre>
 *
 * <p>A left press on the renderer's {@code "track"} part &mdash; or anywhere on the
 * slider if it names none &mdash; sets the value from where the cursor is along
 * it, and dragging keeps doing so until the button comes up. While it has focus,
 * the left and down arrows take a step off and the right and up arrows add one.
 *
 * <p>The value is kept between the bounds and, with a {@link #step}, on a
 * multiple of it from the minimum. Listeners hear it whenever it changes.
 *
 * <p>Game thread only.
 */
public class Slider extends Widget {

    @Getter
    private final double min;

    @Getter
    private final double max;

    /** The spacing values snap to from the minimum; zero for none. */
    @Getter
    private double step;

    @Getter
    private double value;

    private final List<DoubleConsumer> listeners = new ArrayList<>(1);

    /** @throws IllegalArgumentException if {@code min} is not below {@code max} */
    public Slider(double min, double max, double value) {
        Validate.check(min < max, "a slider's minimum must be below its maximum");
        this.min = min;
        this.max = max;
        this.value = clamp(value);
    }

    /** Snaps values to multiples of {@code spacing} from the minimum. Zero, the default, snaps to nothing. */
    public Slider step(double spacing) {
        Validate.check(spacing >= 0d, "a slider's step cannot be negative");
        this.step = spacing;
        this.value = clamp(value);
        return this;
    }

    /** Runs {@code listener} with the new value each time it changes. */
    public Slider onChange(DoubleConsumer listener) {
        listeners.add(Validate.notNull(listener, "listener"));
        return this;
    }

    /** Sets the value, kept between the bounds and on the step, telling the listeners if it changed. */
    public void setValue(double wanted) {
        double next = clamp(wanted);
        if (next == value) {
            return;
        }
        value = next;
        for (DoubleConsumer listener : new ArrayList<>(listeners)) {
            listener.accept(next);
        }
    }

    /** @return how far along the value is, from 0 at the minimum to 1 at the maximum. */
    public float getProgress() {
        return (float) ((value - min) / (max - min));
    }

    /** Sets the value from how far along the slider's range it is, 0 to 1. */
    public void setProgress(double progress) {
        setValue(min + Math.max(0d, Math.min(1d, progress)) * (max - min));
    }

    /** Adds one step, or a hundredth of the range without one. */
    public void increment() {
        setValue(value + keyStep());
    }

    /** Takes one step off, or a hundredth of the range without one. */
    public void decrement() {
        setValue(value - keyStep());
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
        capture();
        followCursor(x);
        return true;
    }

    @Override
    protected void mouseDragged(float x, float y) {
        followCursor(x);
    }

    @Override
    protected boolean keyPressed(Key key, Set<Modifier> modifiers) {
        switch (key) {
            case LEFT:
            case DOWN:
                decrement();
                return true;
            case RIGHT:
            case UP:
                increment();
                return true;
            default:
                return false;
        }
    }

    /** Sets the value from where the cursor is along the track, or along the slider without one. */
    private void followCursor(float x) {
        Bounds track = part("track");
        Bounds along = track != null ? track : getBounds();
        if (along.getWidth() > 0f) {
            setProgress((x - along.getX()) / along.getWidth());
        }
    }

    private double keyStep() {
        return step > 0d ? step : (max - min) / 100d;
    }

    private double clamp(double wanted) {
        double inside = Math.max(min, Math.min(max, wanted));
        if (step > 0d) {
            inside = Math.min(max, min + Math.round((inside - min) / step) * step);
        }
        return inside;
    }

    // --------------------------------------------------------------- chaining

    @Override
    public Slider visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Slider enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Slider grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Slider tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Slider tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
