package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.px.core.setting.Setting;
import lombok.Getter;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Free text, rendered as a text field. */
@Getter
public final class StringSetting extends Setting<String> {

    private int maxLength = 256;

    /** Rejects invalid input before it is stored, so callers never see a bad value. */
    private Predicate<String> validator = text -> true;

    public StringSetting(String name, String defaultValue) {
        super(name, defaultValue);
    }

    public boolean isEmpty() {
        return get().isEmpty();
    }

    public StringSetting maxLength(int limit) {
        this.maxLength = limit;
        return this;
    }

    public StringSetting validatedBy(Predicate<String> predicate) {
        this.validator = predicate;
        return this;
    }

    @Override
    protected String coerce(String incoming) {
        String trimmed = incoming.length() > maxLength ? incoming.substring(0, maxLength) : incoming;
        return validator.test(trimmed) ? trimmed : get();
    }

    @Override
    public String getTypeId() {
        return "string";
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) {
            setSilently(json.getAsString());
        }
    }

    @Override
    public StringSetting describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public StringSetting visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public StringSetting onChange(Consumer<String> listener) {
        super.onChange(listener);
        return this;
    }
}
