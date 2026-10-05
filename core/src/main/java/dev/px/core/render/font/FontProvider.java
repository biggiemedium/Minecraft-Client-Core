package dev.px.core.render.font;

/**
 * Creates {@link Font} handles for the active render backend.
 *
 * <p>Installed by the version adapter. Everything here is backend specific:
 * a NanoVG provider registers the TTF bytes with the nvg context, a legacy
 * provider bakes a glyph atlas, and both return something that satisfies the
 * same interface.
 */
public interface FontProvider {

    /**
     * Loads a font from a resource path bundled with the client.
     *
     * @throws Exception if the resource is missing or unreadable; the caller
     *         reports it and falls back to the default font
     */
    Font load(String name, String resourcePath, float size) throws Exception;

    /** Loads an installed system typeface by family name. */
    Font system(String family, float size);

    /** The game's own font. Always available, and the fallback when loading fails. */
    Font gameDefault();
}
