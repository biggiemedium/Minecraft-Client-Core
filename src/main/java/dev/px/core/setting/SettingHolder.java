package dev.px.core.setting;

import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.registry.Named;
import dev.px.core.render.Color;
import dev.px.core.setting.impl.BindSetting;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.setting.impl.EnumSetting;
import dev.px.core.setting.impl.GroupSetting;
import dev.px.core.setting.impl.MultiEnumSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.setting.impl.RangeSetting;
import dev.px.core.setting.impl.StringSetting;
import dev.px.core.util.Reflect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Anything that owns settings: a module, a HUD element, a client preferences
 * screen, a theme.
 *
 * <p>Declaring the field is the whole registration step. Settings are discovered
 * by reflection the first time {@link #getSettings()} is called, which is after
 * construction has finished, so subclass field initialisers have all run and
 * nothing is missed. That removes the {@code create(new Setting<>(...))} wrapper
 * the old client needed on every line, along with the class of bug where
 * forgetting it left a setting invisible and unsaved.
 *
 * <p>Ordering follows declaration order, base class first. A setting listed as a
 * child of a {@link GroupSetting} is nested under it rather than repeated at the
 * top level.
 *
 * <p>The protected factory shorthands mirror {@link Settings} so subclasses can
 * write {@code bool("Rotations", true)} directly.
 */
public abstract class SettingHolder implements Named {

    private List<Setting<?>> settings;
    private List<Setting<?>> flattened;

    // ----------------------------------------------------------- accessors

    /** @return top-level settings in declaration order. Group children are nested, not repeated. */
    public final List<Setting<?>> getSettings() {
        if (settings == null) {
            discover();
        }
        return settings;
    }

    /** @return every setting including group children, for config and command lookup. */
    public final List<Setting<?>> getAllSettings() {
        if (flattened == null) {
            discover();
        }
        return flattened;
    }

    /** @return only the settings the GUI should currently draw. */
    public final List<Setting<?>> getVisibleSettings() {
        List<Setting<?>> visible = new ArrayList<>();
        for (Setting<?> setting : getSettings()) {
            if (setting.isVisible()) {
                visible.add(setting);
            }
        }
        return visible;
    }

    /** Case-insensitive lookup across group children too, so commands can address any setting. */
    public final Optional<Setting<?>> findSetting(String name) {
        for (Setting<?> setting : getAllSettings()) {
            if (setting.getName().equalsIgnoreCase(name)) {
                return Optional.of(setting);
            }
        }
        return Optional.empty();
    }

    public final void resetSettings() {
        getAllSettings().forEach(Setting::reset);
    }

    // ------------------------------------------------------------ discovery

    private void discover() {
        List<Setting<?>> found = new ArrayList<>();
        for (Field field : Reflect.fieldsOf(getClass())) {
            if (!Setting.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Object value = Reflect.read(field, this);
            if (value != null) {
                found.add((Setting<?>) value);
            }
        }

        // Anything already listed inside a group belongs there, not at the top level.
        Set<Setting<?>> nested = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Setting<?> setting : found) {
            if (setting instanceof GroupSetting) {
                nested.addAll(((GroupSetting) setting).getChildren());
            }
        }

        List<Setting<?>> top = new ArrayList<>(found.size());
        List<Setting<?>> all = new ArrayList<>(found.size());
        int index = 0;
        for (Setting<?> setting : found) {
            setting.bind(this, index++);
            all.add(setting);
            if (!nested.contains(setting)) {
                top.add(setting);
            }
            if (setting instanceof GroupSetting) {
                for (Setting<?> child : ((GroupSetting) setting).getChildren()) {
                    if (!all.contains(child)) {
                        child.bind(this, index++);
                        all.add(child);
                    }
                }
            }
        }

        this.settings = Collections.unmodifiableList(top);
        this.flattened = Collections.unmodifiableList(all);
    }

    // ------------------------------------------------- factory shorthands

    protected static BooleanSetting bool(String name, boolean defaultValue) {
        return Settings.bool(name, defaultValue);
    }

    protected static NumberSetting<Float> number(String name, float defaultValue, float min, float max) {
        return Settings.number(name, defaultValue, min, max);
    }

    protected static NumberSetting<Integer> integer(String name, int defaultValue, int min, int max) {
        return Settings.integer(name, defaultValue, min, max);
    }

    protected static NumberSetting<Double> decimal(String name, double defaultValue, double min, double max) {
        return Settings.decimal(name, defaultValue, min, max);
    }

    protected static NumberSetting<Long> duration(String name, long defaultMillis, long min, long max) {
        return Settings.duration(name, defaultMillis, min, max);
    }

    protected static <E extends Enum<E>> EnumSetting<E> enumOf(String name, E defaultValue) {
        return Settings.enumOf(name, defaultValue);
    }

    @SafeVarargs
    protected static <E extends Enum<E>> MultiEnumSetting<E> multi(String name, Class<E> type, E... enabledByDefault) {
        return Settings.multi(name, type, enabledByDefault);
    }

    protected static StringSetting text(String name, String defaultValue) {
        return Settings.text(name, defaultValue);
    }

    protected static ColorSetting color(String name, Color defaultValue) {
        return Settings.color(name, defaultValue);
    }

    protected static BindSetting bind(String name) {
        return Settings.bind(name);
    }

    protected static BindSetting bind(String name, Key key, Modifier... modifiers) {
        return Settings.bind(name, key, modifiers);
    }

    protected static BindSetting bind(String name, Bind defaultBind) {
        return new BindSetting(name, defaultBind);
    }

    protected static RangeSetting range(String name, double lower, double upper, double min, double max) {
        return Settings.range(name, lower, upper, min, max);
    }

    protected static GroupSetting group(String name) {
        return Settings.group(name);
    }

    protected static GroupSetting group(String name, boolean expanded) {
        return Settings.group(name, expanded);
    }
}
