package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.px.core.setting.Setting;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A collapsible group of related settings.
 *
 * <p>Gives a module with thirty settings a structure the GUI can render without
 * a wall of rows, and gives the config file a nested object rather than a flat
 * namespace where two sub-features cannot both own a setting of the same name.
 *
 * <p>Its own value is whether it is expanded, so that state persists too.
 */
@Getter
public final class GroupSetting extends Setting<Boolean> {

    private final List<Setting<?>> children = new ArrayList<>();

    public GroupSetting(String name, boolean expandedByDefault) {
        super(name, expandedByDefault);
    }

    /**
     * Adds settings to the group.
     *
     * <p>Children declared as fields are still discovered by the owning holder;
     * listing them here moves them under the group instead of leaving them at the
     * top level.
     */
    public GroupSetting containing(Setting<?>... settings) {
        Collections.addAll(children, settings);
        return this;
    }

    public boolean isExpanded() {
        return get();
    }

    public void toggleExpanded() {
        set(!get());
    }

    /** @return only the children the GUI should currently draw. */
    public List<Setting<?>> visibleChildren() {
        List<Setting<?>> visible = new ArrayList<>(children.size());
        for (Setting<?> child : children) {
            if (child.isVisible()) {
                visible.add(child);
            }
        }
        return visible;
    }

    @Override
    public String getTypeId() {
        return "group";
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("expanded", get());
        JsonObject values = new JsonObject();
        for (Setting<?> child : children) {
            values.add(child.getName(), child.toJson());
        }
        json.add("settings", values);
        return json;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return;
        }
        JsonObject object = json.getAsJsonObject();
        if (object.has("expanded")) {
            setSilently(object.get("expanded").getAsBoolean());
        }
        if (object.has("settings") && object.get("settings").isJsonObject()) {
            JsonObject values = object.getAsJsonObject("settings");
            for (Setting<?> child : children) {
                if (values.has(child.getName())) {
                    child.fromJson(values.get(child.getName()));
                }
            }
        }
    }

    @Override
    public String displayValue() {
        return children.size() + " settings";
    }

    @Override
    public GroupSetting describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public GroupSetting visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public GroupSetting onChange(Consumer<Boolean> listener) {
        super.onChange(listener);
        return this;
    }
}
