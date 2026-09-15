package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.setting.Setting;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A keybind, rendered as a click-then-press button. */
public final class BindSetting extends Setting<Bind> {

    public BindSetting(String name, Bind defaultValue) {
        super(name, defaultValue);
    }

    public BindSetting(String name) {
        this(name, Bind.NONE);
    }

    public boolean isBound() {
        return get().isBound();
    }

    public void clear() {
        set(Bind.NONE);
    }

    public void bindTo(Key key, Modifier... modifiers) {
        set(Bind.of(key, modifiers));
    }

    @Override
    public String getTypeId() {
        return "bind";
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get().serialize());
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) {
            setSilently(Bind.deserialize(json.getAsString()));
        }
    }

    @Override
    public String displayValue() {
        return get().getDisplay();
    }

    @Override
    public BindSetting describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public BindSetting visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public BindSetting onChange(Consumer<Bind> listener) {
        super.onChange(listener);
        return this;
    }
}
