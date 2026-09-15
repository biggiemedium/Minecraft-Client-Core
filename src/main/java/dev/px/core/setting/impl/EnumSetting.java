package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.px.core.setting.Setting;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A single choice from an enum, rendered as a dropdown or a cycling button.
 *
 * <p>The old client detected enum settings by ruling out every other type
 * ({@code isEnumSetting()} returned "not a number, string, char, or boolean").
 * Here the options are known up front, which is also what lets the GUI render a
 * proper dropdown and lets a command tab-complete the values.
 *
 * @param <E> the enum type
 */
@Getter
public final class EnumSetting<E extends Enum<E>> extends Setting<E> {

    private final Class<E> type;
    private final List<E> options;

    /** How an option is labelled. Defaults to Title Case of the constant name. */
    private Function<E, String> labeller = EnumSetting::prettify;

    public EnumSetting(String name, E defaultValue) {
        super(name, defaultValue);
        this.type = defaultValue.getDeclaringClass();
        this.options = Arrays.asList(type.getEnumConstants());
    }

    /** Advances to the next option, wrapping. What a click on the button does. */
    public void cycle() {
        set(options.get((options.indexOf(get()) + 1) % options.size()));
    }

    public void cycleBack() {
        int index = options.indexOf(get()) - 1;
        set(options.get(index < 0 ? options.size() - 1 : index));
    }

    public boolean is(E option) {
        return get() == option;
    }

    /** @return true if the current value is any of the given options. */
    @SafeVarargs
    public final boolean isAny(E... candidates) {
        for (E candidate : candidates) {
            if (get() == candidate) {
                return true;
            }
        }
        return false;
    }

    public String labelOf(E option) {
        return labeller.apply(option);
    }

    public EnumSetting<E> labelledBy(Function<E, String> function) {
        this.labeller = function;
        return this;
    }

    @Override
    public String getTypeId() {
        return "enum";
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get().name());
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json == null || !json.isJsonPrimitive()) {
            return;
        }
        String name = json.getAsString();
        for (E option : options) {
            if (option.name().equalsIgnoreCase(name)) {
                setSilently(option);
                return;
            }
        }
        // A constant removed since the config was written: keep the default rather than crash.
    }

    @Override
    public String displayValue() {
        return labelOf(get());
    }

    /** SOME_MODE becomes "Some Mode". */
    private static String prettify(Enum<?> option) {
        String[] words = option.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder text = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }

    @Override
    public EnumSetting<E> describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public EnumSetting<E> visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public EnumSetting<E> onChange(Consumer<E> listener) {
        super.onChange(listener);
        return this;
    }
}
