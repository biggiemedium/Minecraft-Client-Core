package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.px.core.setting.Setting;
import lombok.Getter;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * A bounded numeric value, rendered as a slider.
 *
 * <p>Bounds and step are enforced in {@link #coerce}, so a value out of range
 * cannot enter the setting from the GUI, a command, or a hand-edited config.
 *
 * @param <N> the numeric type: Integer, Long, Float, or Double
 */
@Getter
public final class NumberSetting<N extends Number & Comparable<N>> extends Setting<N> {

    /** Rebuilds an N from the double the slider and step arithmetic work in. */
    private final DoubleFunction<N> fromDouble;

    private N min;
    private N max;

    /** Increment the value snaps to. Zero means continuous. */
    private double step;

    public NumberSetting(String name, N defaultValue, N min, N max, DoubleFunction<N> fromDouble) {
        super(name, defaultValue);
        this.fromDouble = fromDouble;
        this.min = min;
        this.max = max;
        // Re-run the default through coercion so an out-of-range default is caught immediately.
        setSilently(defaultValue);
    }

    // ----------------------------------------------------------- convenience

    public int getInt() {
        return get().intValue();
    }

    public long getLong() {
        return get().longValue();
    }

    public float getFloat() {
        return get().floatValue();
    }

    public double getDouble() {
        return get().doubleValue();
    }

    /** @return where the value sits between min and max, 0..1. What a slider needs. */
    public float progress() {
        double low = min.doubleValue();
        double span = max.doubleValue() - low;
        return span == 0d ? 0f : (float) ((get().doubleValue() - low) / span);
    }

    /** Sets the value from a 0..1 slider position. */
    public void setProgress(float progress) {
        double low = min.doubleValue();
        set(fromDouble.apply(low + (max.doubleValue() - low) * progress));
    }

    // ---------------------------------------------------------------- fluent

    public NumberSetting<N> range(N newMin, N newMax) {
        this.min = newMin;
        this.max = newMax;
        setSilently(get());
        return this;
    }

    public NumberSetting<N> step(double increment) {
        this.step = increment;
        setSilently(get());
        return this;
    }

    // ------------------------------------------------------------ behaviour

    @Override
    protected N coerce(N incoming) {
        N clamped = incoming;
        if (min != null && clamped.compareTo(min) < 0) {
            clamped = min;
        }
        if (max != null && clamped.compareTo(max) > 0) {
            clamped = max;
        }
        if (step > 0d) {
            double snapped = Math.round(clamped.doubleValue() / step) * step;
            clamped = fromDouble.apply(snapped);
            // Snapping can overshoot the bound; pull it back rather than leaking an invalid value.
            if (max != null && clamped.compareTo(max) > 0) {
                clamped = max;
            }
            if (min != null && clamped.compareTo(min) < 0) {
                clamped = min;
            }
        }
        return clamped;
    }

    @Override
    public String getTypeId() {
        return "number";
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) {
            try {
                setSilently(fromDouble.apply(json.getAsDouble()));
            } catch (NumberFormatException ignored) {
                // Leave the current value in place rather than failing the whole profile load.
            }
        }
    }

    @Override
    public String displayValue() {
        double value = getDouble();
        // Integral types and whole-number steps read better without a decimal tail.
        return value == Math.rint(value) && step == Math.rint(step)
                ? String.valueOf((long) value)
                : String.format("%.2f", value);
    }

    @Override
    public NumberSetting<N> describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public NumberSetting<N> visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public NumberSetting<N> onChange(Consumer<N> listener) {
        super.onChange(listener);
        return this;
    }
}
