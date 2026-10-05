package dev.px.core.setting.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.px.core.setting.Setting;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Several choices from an enum at once, rendered as a checkbox list.
 *
 * <p>Replaces the pile of adjacent booleans a module grows for target filters
 * (Players, Mobs, Animals, Invisibles) and the old MultipleBoolean type. One
 * setting means one config entry, one GUI row, and a single {@link #has} call at
 * the use site instead of a chain of boolean ands.
 *
 * @param <E> the enum type
 */
@Getter
public final class MultiEnumSetting<E extends Enum<E>> extends Setting<Set<E>> {

    private final Class<E> type;
    private final List<E> options;

    @SafeVarargs
    public MultiEnumSetting(String name, Class<E> type, E... enabledByDefault) {
        super(name, unmodifiable(toSet(type, enabledByDefault)));
        this.type = type;
        this.options = Arrays.asList(type.getEnumConstants());
    }

    public boolean has(E option) {
        return get().contains(option);
    }

    /** @return whether every one of the given options is selected. */
    @SafeVarargs
    public final boolean hasAll(E... candidates) {
        return get().containsAll(Arrays.asList(candidates));
    }

    /** @return whether any of the given options is selected. */
    @SafeVarargs
    public final boolean hasAny(E... candidates) {
        for (E candidate : candidates) {
            if (get().contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return get().isEmpty();
    }

    public void toggle(E option) {
        EnumSet<E> updated = copy(get());
        if (!updated.remove(option)) {
            updated.add(option);
        }
        set(unmodifiable(updated));
    }

    public void select(E option, boolean enabled) {
        EnumSet<E> updated = copy(get());
        if (enabled) {
            updated.add(option);
        } else {
            updated.remove(option);
        }
        set(unmodifiable(updated));
    }

    public void selectAll() {
        set(unmodifiable(EnumSet.allOf(type)));
    }

    public void clear() {
        set(unmodifiable(EnumSet.noneOf(type)));
    }

    @Override
    public String getTypeId() {
        return "multi-enum";
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (E option : options) {
            if (get().contains(option)) {
                array.add(option.name());
            }
        }
        return array;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json == null || !json.isJsonArray()) {
            return;
        }
        EnumSet<E> loaded = EnumSet.noneOf(type);
        for (JsonElement element : json.getAsJsonArray()) {
            for (E option : options) {
                if (option.name().equalsIgnoreCase(element.getAsString())) {
                    loaded.add(option);
                }
            }
        }
        setSilently(unmodifiable(loaded));
    }

    @Override
    public String displayValue() {
        if (get().isEmpty()) {
            return "None";
        }
        if (get().size() == options.size()) {
            return "All";
        }
        return get().stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    @SafeVarargs
    private static <E extends Enum<E>> EnumSet<E> toSet(Class<E> type, E... values) {
        EnumSet<E> set = EnumSet.noneOf(type);
        Collections.addAll(set, values);
        return set;
    }

    private static <E extends Enum<E>> Set<E> unmodifiable(EnumSet<E> set) {
        return Collections.unmodifiableSet(set);
    }

    /** EnumSet.copyOf rejects an empty non-EnumSet, so build the copy through the type. */
    private EnumSet<E> copy(Set<E> source) {
        EnumSet<E> result = EnumSet.noneOf(type);
        result.addAll(source);
        return result;
    }

    @Override
    public MultiEnumSetting<E> describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public MultiEnumSetting<E> visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public MultiEnumSetting<E> onChange(Consumer<Set<E>> listener) {
        super.onChange(listener);
        return this;
    }
}
