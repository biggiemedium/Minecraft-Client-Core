package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.px.core.setting.Setting;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** An on/off toggle. Rendered as a checkbox or switch. */
public final class BooleanSetting extends Setting<Boolean> implements BooleanSupplier {

    public BooleanSetting(String name, boolean defaultValue) {
        super(name, defaultValue);
    }

    public boolean isOn() {
        return get();
    }

    public void toggle() {
        set(!get());
    }

    /** Lets a boolean setting be passed anywhere a {@link BooleanSupplier} is wanted. */
    @Override
    public boolean getAsBoolean() {
        return get();
    }

    @Override
    public String getTypeId() {
        return "boolean";
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) {
            setSilently(json.getAsBoolean());
        }
    }

    // Covariant overrides so the base fluent methods chain without losing the type.

    @Override
    public BooleanSetting describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public BooleanSetting visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public BooleanSetting onChange(Consumer<Boolean> listener) {
        super.onChange(listener);
        return this;
    }
}
