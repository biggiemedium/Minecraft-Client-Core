package dev.px.core.gui;

import dev.px.core.render.Color;
import dev.px.core.render.theme.ThemeService;

/**
 * Every colour and measurement the GUI draws with, in one place.
 *
 * <p>Colours are read from the active {@link ThemeService} on each call rather
 * than cached. A config load applies settings silently, so anything that cached
 * a theme colour at construction would draw the previous theme until the client
 * restarted &mdash; the same reason {@link ThemeService#getActive()} resolves on
 * demand.
 *
 * <p>The service is injected once by {@link GuiService#start()}, following the
 * pattern the setting and render packages already use
 * ({@code ColorSetting.bindThemeSupplier}, {@code Render.setDefaultFont}). Until
 * then every colour falls back to a neutral constant, so a component built
 * before startup &mdash; or in a test with no client at all &mdash; draws
 * something rather than throwing.
 *
 * <p>The metrics are constants because they are the grid the whole GUI is laid
 * out on: a row is one row high everywhere, and a component that invents its own
 * row height stops lining up with every other component in the panel.
 */
public final class GuiStyle {

    /** Height of a single setting row, and of a module button. */
    public static final float ROW_HEIGHT = 13f;

    /** Inset between a panel's edge and its children. */
    public static final float PADDING = 3f;

    /** Gap between stacked children. */
    public static final float SPACING = 1f;

    /** Width of a category window. Fixed, so nothing has to measure text to lay out. */
    public static final float WINDOW_WIDTH = 100f;

    /** Height of a window's draggable title bar. */
    public static final float TITLE_HEIGHT = 15f;

    /** Indent applied to a row nested inside a group or a dropdown. */
    public static final float INDENT = 6f;

    private static volatile ThemeService themes;

    private GuiStyle() {
    }

    /** Points the GUI at the active theme. Called by {@link GuiService#start()}. */
    public static void bind(ThemeService service) {
        themes = service;
    }

    /** @return whether a theme service is installed, i.e. whether colours are real. */
    public static boolean isBound() {
        return themes != null;
    }

    // ---------------------------------------------------------------- colours

    /** The colour behind a whole window. */
    public static Color background() {
        ThemeService service = themes;
        return service == null ? Color.of(18, 18, 22, 220) : service.getBackground();
    }

    /** The colour behind a row sitting on the background. */
    public static Color surface() {
        ThemeService service = themes;
        return service == null ? Color.of(32, 32, 38, 220) : service.getSurface();
    }

    /** The primary accent: selection, slider fill, an enabled module. */
    public static Color accent() {
        ThemeService service = themes;
        return service == null ? Color.of(120, 200, 255) : service.getPrimary();
    }

    /**
     * @param progress 0 at the primary accent, 1 at the secondary
     * @return the accent gradient sampled at that point, for a fill that spans a row
     */
    public static Color accent(float progress) {
        ThemeService service = themes;
        return service == null ? accent() : service.accent(progress);
    }

    public static Color text() {
        ThemeService service = themes;
        return service == null ? Color.of(235, 235, 240) : service.getTextColor().resolve();
    }

    /**
     * The scrim drawn over the game behind an open screen.
     *
     * <p>Derived from the theme's background rather than being a fixed black, so
     * a light theme dims to something light instead of punching a dark hole in
     * its own palette.
     */
    public static Color backdrop() {
        return background().withAlpha(150);
    }

    /** Dimmer text: a setting's value, a disabled module, a hint. */
    public static Color textMuted() {
        ThemeService service = themes;
        return service == null ? Color.of(150, 150, 160) : service.getActive().getTextMuted();
    }

    public static Color outline() {
        ThemeService service = themes;
        return service == null ? Color.of(70, 70, 80) : service.getActive().getOutline();
    }

    /** Corner rounding, configured once in the theme and applied everywhere. */
    public static float radius() {
        ThemeService service = themes;
        return service == null ? 3f : service.getRadiusValue();
    }

    /**
     * @return {@code radius()} capped so it cannot exceed half of the smaller side
     *
     * <p>A 16px radius on a 13px row draws a lozenge, or whatever the backend does
     * with an impossible radius. Capping is the caller's job in principle and
     * nobody's in practice, so it lives here.
     */
    public static float radius(float width, float height) {
        return Math.min(radius(), Math.min(width, height) / 2f);
    }
}
