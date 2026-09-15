package dev.px.core.render.font;

import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The fonts the client draws with, addressed by name.
 *
 * <p>Loading a font is backend and version specific, so that work belongs to a
 * {@link FontProvider} the adapter installs. Core only keeps the handles and
 * decides which one is the default, which is what lets a HUD element ask for
 * "title" without knowing whether that came from a TTF, the game font, or a
 * system typeface.
 */
@Getter
@RequiredArgsConstructor
public final class FontService implements Service {

    private final CoreLogger logger;

    private final Map<String, Font> fonts = new LinkedHashMap<>();

    /** Installed by the adapter before startup. */
    @Setter
    private FontProvider provider;

    private Font defaultFont;

    @Override
    public String getName() {
        return "Fonts";
    }

    @Override
    public void start() {
        if (provider == null) {
            logger.warn("No FontProvider installed; text will not render");
            return;
        }
        if (defaultFont == null) {
            defaultFont = register("default", provider.gameDefault());
        }
    }

    /**
     * Loads a font through the installed provider and registers it under {@code name}.
     *
     * @param resourcePath a path the provider understands, usually a bundled TTF
     * @return the loaded font, or the default if loading failed
     */
    public Font load(String name, String resourcePath, float size) {
        if (provider == null) {
            throw new IllegalStateException("No FontProvider installed; call setProvider before startup");
        }
        try {
            return register(name, provider.load(name, resourcePath, size));
        } catch (Exception e) {
            // A missing font should degrade to readable text, not crash the client.
            logger.error("Failed to load font " + name + " from " + resourcePath, e);
            return getDefault();
        }
    }

    public Font register(String name, Font font) {
        fonts.put(name.toLowerCase(Locale.ROOT), font);
        if (defaultFont == null) {
            defaultFont = font;
        }
        return font;
    }

    public Optional<Font> find(String name) {
        return Optional.ofNullable(fonts.get(name.toLowerCase(Locale.ROOT)));
    }

    /** @return the named font, or the default when it is not registered. */
    public Font get(String name) {
        return find(name).orElseGet(this::getDefault);
    }

    public Font getDefault() {
        if (defaultFont == null) {
            throw new IllegalStateException("No fonts registered; install a FontProvider before startup");
        }
        return defaultFont;
    }

    public void setDefault(String name) {
        find(name).ifPresent(font -> this.defaultFont = font);
    }
}
