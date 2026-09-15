package dev.px.core.config;

import com.google.gson.JsonObject;
import dev.px.core.setting.Setting;
import dev.px.core.setting.SettingHolder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A config section backed by one {@link SettingHolder}.
 *
 * <p>Used for the singletons: the theme, the command prefix, client preferences.
 * Each setting serialises itself, so adding a new setting type needs no change
 * here &mdash; unlike the old file utility, which had a branch per type and
 * silently dropped anything it did not recognise.
 *
 * <p>Only top-level settings are written; a {@link dev.px.core.setting.impl.GroupSetting}
 * serialises its own children, so nesting round-trips without special handling.
 */
@Getter
@RequiredArgsConstructor
public final class SettingsSection implements ConfigSection {

    private final String id;
    private final SettingHolder holder;

    @Override
    public JsonObject save() {
        JsonObject json = new JsonObject();
        for (Setting<?> setting : holder.getSettings()) {
            json.add(setting.getName(), setting.toJson());
        }
        return json;
    }

    @Override
    public void load(JsonObject json) {
        for (Setting<?> setting : holder.getSettings()) {
            if (json.has(setting.getName())) {
                setting.fromJson(json.get(setting.getName()));
            }
        }
    }
}
