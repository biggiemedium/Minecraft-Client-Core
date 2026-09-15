package dev.px.core.config;

import com.google.gson.JsonObject;

/**
 * One addressable block of a config file.
 *
 * <p>Modules are a section, HUD elements are a section, the theme is a section,
 * and a client can add its own. Sections are read independently, so a section
 * that fails to load, or one written by a build that had a feature this one does
 * not, leaves the rest of the config intact.
 */
public interface ConfigSection {

    /** Stable key this section is stored under. Renaming it orphans saved data. */
    String getId();

    JsonObject save();

    /**
     * Applies saved state.
     *
     * <p>Must tolerate missing and unexpected keys: a config is user-editable and
     * outlives the build that wrote it.
     */
    void load(JsonObject json);
}
