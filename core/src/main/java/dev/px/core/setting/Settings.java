package dev.px.core.setting;

import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.math.Range;
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

/**
 * Factories for every setting type.
 *
 * <p>{@link SettingHolder} inherits these as protected shorthands, so inside a
 * module or HUD element you write {@code bool("Rotations", true)} with no
 * qualifier and no registration call. Static-import this class anywhere else.
 *
 * <p>Numeric factories take their bounds up front because an unbounded slider is
 * always a bug; use {@code .step(...)} afterwards to snap the value.
 */
public final class Settings {

    private Settings() {
    }

    public static BooleanSetting bool(String name, boolean defaultValue) {
        return new BooleanSetting(name, defaultValue);
    }

    /** A float slider. The default for most numeric settings. */
    public static NumberSetting<Float> number(String name, float defaultValue, float min, float max) {
        return new NumberSetting<>(name, defaultValue, min, max, value -> (float) value);
    }

    public static NumberSetting<Integer> integer(String name, int defaultValue, int min, int max) {
        return new NumberSetting<>(name, defaultValue, min, max, value -> (int) Math.round(value)).step(1d);
    }

    public static NumberSetting<Double> decimal(String name, double defaultValue, double min, double max) {
        return new NumberSetting<>(name, defaultValue, min, max, value -> value);
    }

    public static NumberSetting<Long> duration(String name, long defaultMillis, long min, long max) {
        return new NumberSetting<>(name, defaultMillis, min, max, value -> Math.round(value)).step(1d);
    }

    /** A dropdown over an enum. The selected constant is the default. */
    public static <E extends Enum<E>> EnumSetting<E> enumOf(String name, E defaultValue) {
        return new EnumSetting<>(name, defaultValue);
    }

    /** A checkbox list over an enum. Pass the constants that start selected. */
    @SafeVarargs
    public static <E extends Enum<E>> MultiEnumSetting<E> multi(String name, Class<E> type, E... enabledByDefault) {
        return new MultiEnumSetting<>(name, type, enabledByDefault);
    }

    public static StringSetting text(String name, String defaultValue) {
        return new StringSetting(name, defaultValue);
    }

    public static ColorSetting color(String name, Color defaultValue) {
        return new ColorSetting(name, defaultValue);
    }

    public static BindSetting bind(String name) {
        return new BindSetting(name);
    }

    public static BindSetting bind(String name, Key key, Modifier... modifiers) {
        return new BindSetting(name, Bind.of(key, modifiers));
    }

    /** A two-handled slider: the lower and upper of a randomised value. */
    public static RangeSetting range(String name, double lower, double upper, double min, double max) {
        return new RangeSetting(name, Range.of(lower, upper), min, max);
    }

    public static GroupSetting group(String name) {
        return new GroupSetting(name, false);
    }

    public static GroupSetting group(String name, boolean expanded) {
        return new GroupSetting(name, expanded);
    }
}
