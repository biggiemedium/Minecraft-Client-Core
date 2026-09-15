package dev.px.core.config;

import com.google.gson.JsonObject;
import dev.px.core.module.Toggleable;
import dev.px.core.registry.Registry;
import dev.px.core.setting.Setting;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A config section backed by a registry of {@link Toggleable}s: modules, HUD
 * elements, anything with an on/off state and settings.
 *
 * <p>Entries are keyed by name rather than by index, so adding, removing or
 * reordering registrations cannot shift a saved config onto the wrong entry.
 * An entry in the file that no longer exists is ignored, and one that exists but
 * is absent from the file keeps its defaults, so configs survive version changes
 * in both directions.
 *
 * <p>Enabled state is applied through {@code setEnabled}, which fires the
 * lifecycle hooks, while setting values are applied silently. That ordering
 * matters: settings are restored first, so a module's {@code onEnable} sees the
 * configuration it is meant to start with.
 */
@Getter
@RequiredArgsConstructor
public final class ToggleableSection<T extends Toggleable> implements ConfigSection {

    private static final String ENABLED = "enabled";
    private static final String SETTINGS = "settings";

    private final String id;
    private final Registry<T> registry;

    @Override
    public JsonObject save() {
        JsonObject json = new JsonObject();
        for (T entry : registry) {
            JsonObject values = new JsonObject();
            for (Setting<?> setting : entry.getSettings()) {
                values.add(setting.getName(), setting.toJson());
            }
            JsonObject node = new JsonObject();
            node.addProperty(ENABLED, entry.isEnabled());
            node.add(SETTINGS, values);
            json.add(entry.getName(), node);
        }
        return json;
    }

    @Override
    public void load(JsonObject json) {
        for (T entry : registry) {
            if (!json.has(entry.getName()) || !json.get(entry.getName()).isJsonObject()) {
                continue;
            }
            JsonObject node = json.getAsJsonObject(entry.getName());

            JsonObject values = Json.child(node, SETTINGS);
            for (Setting<?> setting : entry.getSettings()) {
                if (values.has(setting.getName())) {
                    setting.fromJson(values.get(setting.getName()));
                }
            }

            // Applied last, so the enable hook runs against restored settings.
            if (node.has(ENABLED)) {
                entry.setEnabled(node.get(ENABLED).getAsBoolean());
            }
        }
    }
}
