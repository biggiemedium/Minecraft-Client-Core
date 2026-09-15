package dev.px.core.render.theme;

import dev.px.core.registry.Registry;
import dev.px.core.render.Color;
import dev.px.core.service.Service;
import dev.px.core.setting.SettingHolder;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.setting.impl.StringSetting;
import lombok.Getter;

/**
 * The registered themes plus the appearance settings that apply across all of them.
 *
 * <p>Being a {@link SettingHolder} means the corner radius, opacity and text
 * colour persist through the ordinary config mechanism instead of the separate
 * bespoke theme file the old client wrote.
 *
 * <p>The selected theme is stored by name rather than by index, so adding or
 * reordering themes cannot silently change what a saved config resolves to.
 */
@Getter
public final class ThemeService extends SettingHolder implements Service {

    private final Registry<Theme> themes = new Registry<>();

    private final StringSetting selected = text("Theme", "");
    private final NumberSetting<Integer> radius = integer("Corner Radius", 4, 0, 16)
            .describe("Rounding applied to panels and buttons");
    private final NumberSetting<Integer> opacity = integer("Opacity", 200, 0, 255)
            .describe("Background opacity across the client");
    private final ColorSetting textColor = color("Text Colour", Color.WHITE);
    private final BooleanSetting backgrounds = bool("Element Backgrounds", true)
            .describe("Draw a panel behind HUD elements");

    private Theme active;

    @Override
    public String getName() {
        return "Themes";
    }

    @Override
    public void start() {
        if (themes.isEmpty()) {
            // A client that registers no themes still needs something to draw with.
            themes.register(Theme.of("Default", Color.of(120, 200, 255), Color.of(160, 140, 255)));
        }
        // Colour settings set to follow the theme resolve through this.
        ColorSetting.bindThemeSupplier(this::getPrimary);
    }

    /** Registers a theme. Call before {@link #start()} so a saved selection can find it. */
    public Theme register(Theme theme) {
        return themes.register(theme);
    }

    /**
     * @return the selected theme.
     *
     * <p>Resolved from the {@link #selected} setting on demand rather than cached
     * on change. A config load applies settings silently, on purpose, so anything
     * that caches a value on a change listener goes stale the moment a profile is
     * loaded. Deriving it here cannot.
     */
    public Theme getActive() {
        String wanted = selected.get();
        if (active == null || !active.getName().equalsIgnoreCase(wanted)) {
            resolve(wanted);
        }
        return active;
    }

    public void setActive(Theme theme) {
        selected.set(theme.getName());
        this.active = theme;
    }

    public void setActive(String name) {
        themes.find(name).ifPresent(this::setActive);
    }

    private void resolve(String wanted) {
        // Fall back to the first registered theme rather than failing: a saved
        // profile may name a theme this build no longer ships.
        active = wanted.isEmpty() ? themes.all().get(0) : themes.find(wanted).orElse(themes.all().get(0));
        if (!active.getName().equals(wanted)) {
            selected.setSilently(active.getName());
        }
    }

    // Shorthands, because these three are read on nearly every draw call.

    public Color getPrimary() {
        return getActive().getPrimary();
    }

    public Color getSecondary() {
        return getActive().getSecondary();
    }

    /** @return the accent gradient colour at {@code progress}, at the configured opacity. */
    public Color accent(float progress) {
        return getActive().accent(progress).withAlpha(opacity.getInt());
    }

    public Color getBackground() {
        return getActive().getBackground().withAlpha(opacity.getInt());
    }

    public Color getSurface() {
        return getActive().getSurface().withAlpha(opacity.getInt());
    }

    public float getRadiusValue() {
        return radius.getFloat();
    }

}
