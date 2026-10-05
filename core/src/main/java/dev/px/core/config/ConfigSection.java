package dev.px.core.config;

import com.google.gson.JsonObject;

/**
 * One file of config: what to save, and how to apply it again.
 *
 * <p>Modules are a section, HUD elements are a section, the theme is a section,
 * and a client can add its own. Each is saved to a file of its own, in every
 * profile or shared by all of them, wherever {@link ConfigService#register} or
 * {@link ConfigService#place} puts it, and encrypted only if you
 * {@linkplain ConfigService#encrypt ask}. Sections are read independently, so a
 * section that fails to load, or one written by a build that had a feature this
 * one does not, leaves the rest of the config intact.
 */
public interface ConfigSection {

    /**
     * Stable key this section is known by, and its file name unless it is placed
     * elsewhere. Renaming it orphans saved data.
     */
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
