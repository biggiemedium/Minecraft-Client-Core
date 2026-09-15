package dev.px.core.module;

import dev.px.core.registry.Named;

/**
 * A module grouping, such as Combat or Movement.
 *
 * <p>Deliberately an interface rather than an enum: Core cannot know which
 * categories a given client wants, and hard-coding them would mean editing Core
 * to add one. Implement it with an enum in your client and the ergonomics of an
 * enum are preserved:
 *
 * <pre>{@code
 * public enum Categories implements Category {
 *     COMBAT("Combat"), MOVEMENT("Movement"), RENDER("Render");
 *
 *     private final String name;
 *     Categories(String name) { this.name = name; }
 *     public String getName() { return name; }
 * }
 * }</pre>
 */
public interface Category extends Named {

    @Override
    String getName();

    /** Sort position in the GUI. Lower first; ties fall back to registration order. */
    default int getOrder() {
        return 0;
    }

    /**
     * Optional icon key, resolved by whatever the GUI uses for icons (an atlas
     * name, a glyph, a resource path). Core never interprets it.
     */
    default String getIcon() {
        return "";
    }
}
