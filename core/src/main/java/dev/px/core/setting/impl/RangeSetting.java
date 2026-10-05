package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.px.core.math.Range;
import dev.px.core.setting.Setting;
import lombok.Getter;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A pair of bounds, rendered as a two-handled slider.
 *
 * <p>The pattern this replaces is everywhere in a client: min CPS plus max CPS,
 * min delay plus max delay, declared as two separate settings that nothing stops
 * from crossing over. One {@link Range} keeps them ordered and gives
 * {@link Range#random()} for the randomised value they are almost always used for.
 */
@Getter
public final class RangeSetting extends Setting<Range> {

    private double min;
    private double max;
    private double step;

    public RangeSetting(String name, Range defaultValue, double min, double max) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
        setSilently(defaultValue);
    }

    public double randomValue() {
        return get().random();
    }

    public int randomInt() {
        return get().randomInt();
    }

    public void setLower(double value) {
        set(get().withLower(value));
    }

    public void setUpper(double value) {
        set(get().withUpper(value));
    }

    public RangeSetting limits(double newMin, double newMax) {
        this.min = newMin;
        this.max = newMax;
        setSilently(get());
        return this;
    }

    public RangeSetting step(double increment) {
        this.step = increment;
        setSilently(get());
        return this;
    }

    @Override
    protected Range coerce(Range incoming) {
        double lower = snap(Math.max(min, Math.min(max, incoming.getLower())));
        double upper = snap(Math.max(min, Math.min(max, incoming.getUpper())));
        return Range.of(lower, upper);
    }

    private double snap(double value) {
        return step > 0d ? Math.round(value / step) * step : value;
    }

    @Override
    public String getTypeId() {
        return "range";
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("lower", get().getLower());
        json.addProperty("upper", get().getUpper());
        return json;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.has("lower") && object.has("upper")) {
                setSilently(Range.of(object.get("lower").getAsDouble(), object.get("upper").getAsDouble()));
            }
        }
    }

    @Override
    public String displayValue() {
        return get().toString();
    }

    @Override
    public RangeSetting describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public RangeSetting visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public RangeSetting onChange(Consumer<Range> listener) {
        super.onChange(listener);
        return this;
    }
}
