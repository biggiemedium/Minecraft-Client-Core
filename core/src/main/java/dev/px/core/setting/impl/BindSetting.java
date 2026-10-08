package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.px.core.input.Bind;
import dev.px.core.input.BindMode;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.setting.Setting;
import dev.px.core.util.Validate;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A keybind, rendered as a click-then-press button.
 *
 * <pre>{@code
 * private final BindSetting zoom = bind("Zoom", Bind.hold(Key.C));
 * }</pre>
 *
 * <p>The {@link BindMode} is part of the value. Rebinding the key or clearing it
 * keeps the mode, and {@link #setMode} changes the mode and keeps the key.
 */
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

    /** Unbinds the key, keeping the mode. */
    public void clear() {
        set(Bind.NONE.withMode(getMode()));
    }

    /** Binds a new key, keeping the mode. */
    public void bindTo(Key key, Modifier... modifiers) {
        set(Bind.of(key, modifiers).withMode(getMode()));
    }

    /** Binds a new mouse button, keeping the mode. */
    public void bindTo(MouseButton button, Modifier... modifiers) {
        set(Bind.of(button, modifiers).withMode(getMode()));
    }

    public BindMode getMode() {
        return get().getMode();
    }

    /** Changes what the bind does, keeping the key. */
    public void setMode(BindMode mode) {
        set(get().withMode(Validate.notNull(mode, "mode of " + getName())));
    }

    public boolean isHold() {
        return get().isHold();
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
