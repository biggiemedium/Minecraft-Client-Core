package dev.px.core.setting;

import com.google.gson.JsonElement;
import dev.px.core.registry.Named;
import dev.px.core.util.Validate;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A single named, typed, persistable value owned by a {@link SettingHolder}.
 *
 * <p>Replaces the old {@code Setting<T>} with its five overlapping constructors
 * and {@code instanceof} type-sniffing. Each concrete subclass knows its own
 * type, so GUI code dispatches on the class rather than guessing from the value,
 * and serialisation is the setting's own job instead of a giant switch in a
 * file utility.
 *
 * <p>Instances are created through the factories on {@link Settings} (or the
 * shorthands inherited from {@link SettingHolder}) and are discovered
 * automatically &mdash; declaring the field is the whole registration step.
 *
 * @param <T> the value type
 */
@Getter
public abstract class Setting<T> implements Named {

    private final String name;
    private final T defaultValue;

    private T value;
    private String description = "";

    /** Declaration index within the owning holder; drives GUI ordering. */
    private int order = Integer.MAX_VALUE;

    private SettingHolder owner;
    private BooleanSupplier visibility;

    private final List<Consumer<T>> changeListeners = new ArrayList<>(0);

    protected Setting(String name, T defaultValue) {
        this.name = Validate.notBlank(name, "setting name");
        this.defaultValue = Validate.notNull(defaultValue, "default value of setting " + name);
        this.value = defaultValue;
    }

    // ---------------------------------------------------------------- value

    /** @return the current value. Never null. */
    public T get() {
        return value;
    }

    /**
     * Assigns a value, notifying listeners and posting a {@link SettingChangeEvent}.
     * Assigning the value it already holds does nothing.
     */
    public void set(T newValue) {
        Validate.notNull(newValue, "value of setting " + name);
        T coerced = coerce(newValue);
        if (coerced.equals(value)) {
            return;
        }
        T previous = value;
        this.value = coerced;
        for (Consumer<T> listener : changeListeners) {
            listener.accept(coerced);
        }
        SettingChangeEvent.post(this, previous, coerced);
    }

    /**
     * Assigns a value without notifying anyone.
     *
     * <p>Used when loading a config: firing a change event per setting during a
     * profile load would have modules reacting to half-applied state.
     */
    public void setSilently(T newValue) {
        this.value = coerce(Validate.notNull(newValue, "value of setting " + name));
    }

    public void reset() {
        set(defaultValue);
    }

    public boolean isDefault() {
        return value.equals(defaultValue);
    }

    /**
     * Hook for subclasses to clamp or normalise an incoming value.
     * Returns the value unchanged by default.
     */
    protected T coerce(T incoming) {
        return incoming;
    }

    // ------------------------------------------------------------ visibility

    /** @return whether the GUI should currently show this setting. */
    public boolean isVisible() {
        return visibility == null || visibility.getAsBoolean();
    }

    // -------------------------------------------------------------- fluent

    public Setting<T> describe(String text) {
        this.description = text == null ? "" : text;
        return this;
    }

    /**
     * Shows this setting only while {@code condition} holds.
     *
     * <pre>{@code
     * BooleanSetting autoBlock = bool("Auto Block", true);
     * EnumSetting<Mode> mode    = enumOf("Block Mode", Mode.VANILLA).visibleWhen(autoBlock);
     * }</pre>
     */
    public Setting<T> visibleWhen(BooleanSupplier condition) {
        this.visibility = condition;
        return this;
    }

    /** Runs {@code listener} whenever the value changes through {@link #set}. */
    public Setting<T> onChange(Consumer<T> listener) {
        changeListeners.add(Validate.notNull(listener, "listener"));
        return this;
    }

    // ---------------------------------------------------------- persistence

    /** Stable identifier written to config files so a value survives renames of the class. */
    public abstract String getTypeId();

    public abstract JsonElement toJson();

    /** Applies a persisted value. Must tolerate malformed input by leaving the value alone. */
    public abstract void fromJson(JsonElement json);

    /** @return the value rendered for display, e.g. on a slider or in chat. */
    public String displayValue() {
        return String.valueOf(value);
    }

    // ------------------------------------------------------------- internal

    void bind(SettingHolder holder, int index) {
        this.owner = holder;
        this.order = index;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + name + "=" + displayValue() + ")";
    }
}
