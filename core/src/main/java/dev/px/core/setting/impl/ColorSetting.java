package dev.px.core.setting.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.px.core.render.Color;
import dev.px.core.setting.Setting;
import lombok.Getter;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A colour, rendered as a picker.
 *
 * <p>Supports two dynamic modes that most clients end up reimplementing per
 * module: a rainbow cycle and a sync to the active {@link dev.px.core.render.theme.Theme}.
 * {@link #resolve()} applies whichever is active, so call sites just draw with
 * the result and never branch on the mode.
 */
@Getter
public final class ColorSetting extends Setting<Color> {

    /** Separates the colour from its mode flags in the persisted string. */
    private static final String FLAG_SEPARATOR = "|";

    private boolean allowAlpha = true;
    private boolean rainbow;
    private boolean syncToTheme;

    /** Full rainbow cycles per second. */
    private float rainbowSpeed = 0.2f;

    /** Supplies the theme colour when {@link #syncToTheme} is on. Injected by Core at startup. */
    private static volatile java.util.function.Supplier<Color> themeColor;

    public ColorSetting(String name, Color defaultValue) {
        super(name, defaultValue);
    }

    public static void bindThemeSupplier(java.util.function.Supplier<Color> supplier) {
        themeColor = supplier;
    }

    /**
     * @return the colour to actually draw with, after applying rainbow or theme
     *         sync. Use this everywhere except in the picker itself.
     */
    public Color resolve() {
        if (rainbow) {
            float phase = (System.currentTimeMillis() % (long) (1000f / rainbowSpeed)) * rainbowSpeed / 1000f;
            return Color.hsb(phase, 1f, 1f).withAlpha(get().getAlpha());
        }
        if (syncToTheme) {
            java.util.function.Supplier<Color> supplier = themeColor;
            if (supplier != null) {
                return supplier.get().withAlpha(get().getAlpha());
            }
        }
        return get();
    }

    /** Convenience for the common case of drawing at a fixed opacity. */
    public Color resolve(int alpha) {
        return resolve().withAlpha(alpha);
    }

    public ColorSetting opaque() {
        this.allowAlpha = false;
        return this;
    }

    public ColorSetting rainbow(boolean enabled) {
        this.rainbow = enabled;
        return this;
    }

    public ColorSetting rainbowSpeed(float cyclesPerSecond) {
        this.rainbowSpeed = cyclesPerSecond;
        return this;
    }

    public ColorSetting syncToTheme(boolean enabled) {
        this.syncToTheme = enabled;
        return this;
    }

    @Override
    protected Color coerce(Color incoming) {
        return allowAlpha ? incoming : incoming.withAlpha(255);
    }

    @Override
    public String getTypeId() {
        return "color";
    }

    @Override
    public JsonElement toJson() {
        // Flags ride along in the string so the setting stays one JSON value.
        return new JsonPrimitive(get().toHex() + (rainbow ? FLAG_SEPARATOR + "rainbow" : "") + (syncToTheme ? FLAG_SEPARATOR + "theme" : ""));
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json == null || !json.isJsonPrimitive()) {
            return;
        }
        String[] parts = json.getAsString().split(Pattern.quote(FLAG_SEPARATOR));
        try {
            setSilently(Color.parse(parts[0]));
        } catch (RuntimeException ignored) {
            return;
        }
        this.rainbow = false;
        this.syncToTheme = false;
        for (int i = 1; i < parts.length; i++) {
            if ("rainbow".equals(parts[i])) {
                this.rainbow = true;
            } else if ("theme".equals(parts[i])) {
                this.syncToTheme = true;
            }
        }
    }

    @Override
    public String displayValue() {
        return rainbow ? "Rainbow" : syncToTheme ? "Theme" : get().toHex();
    }

    @Override
    public ColorSetting describe(String text) {
        super.describe(text);
        return this;
    }

    @Override
    public ColorSetting visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public ColorSetting onChange(Consumer<Color> listener) {
        super.onChange(listener);
        return this;
    }
}
