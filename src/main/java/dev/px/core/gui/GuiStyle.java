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
 * <p>The metrics are a grid, not constants. A row is one row high everywhere and
 * a component that invents its own height stops lining up with the rest of the
 * panel &mdash; but <em>which</em> height that is belongs to whoever is building
 * the client. {@link #metrics(Metrics)} replaces them wholesale, and every
 * component Core ships reads them through these accessors, so a chunkier or
 * tighter GUI costs one call rather than nine rewritten rows.
 *
 * <pre>{@code
 * GuiStyle.metrics(GuiStyle.Metrics.builder()
 *         .rowHeight(18f)
 *         .padding(5f)
 *         .windowWidth(130f)
 *         .build());
 * }</pre>
 */
public final class GuiStyle {

    private static volatile ThemeService themes;
    private static volatile Metrics metrics = Metrics.defaults();

    private GuiStyle() {
    }

    // ---------------------------------------------------------------- metrics

    /**
     * The measurements the GUI is laid out on.
     *
     * <p>Immutable, and replaced as a set rather than field by field: a row
     * height that no longer matches the padding around it is how a GUI stops
     * lining up, and swapping the whole grid at once makes that hard to do by
     * accident.
     */
    public static final class Metrics {

        private final float rowHeight;
        private final float padding;
        private final float spacing;
        private final float windowWidth;
        private final float titleHeight;
        private final float indent;

        private Metrics(Builder builder) {
            this.rowHeight = builder.rowHeight;
            this.padding = builder.padding;
            this.spacing = builder.spacing;
            this.windowWidth = builder.windowWidth;
            this.titleHeight = builder.titleHeight;
            this.indent = builder.indent;
        }

        public static Metrics defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public float getRowHeight() {
            return rowHeight;
        }

        public float getPadding() {
            return padding;
        }

        public float getSpacing() {
            return spacing;
        }

        public float getWindowWidth() {
            return windowWidth;
        }

        public float getTitleHeight() {
            return titleHeight;
        }

        public float getIndent() {
            return indent;
        }

        public static final class Builder {

            private float rowHeight = 13f;
            private float padding = 3f;
            private float spacing = 1f;
            private float windowWidth = 100f;
            private float titleHeight = 15f;
            private float indent = 6f;

            /** Height of a single setting row, and of a module button. */
            public Builder rowHeight(float value) {
                this.rowHeight = value;
                return this;
            }

            /** Inset between a panel's edge and its children. */
            public Builder padding(float value) {
                this.padding = value;
                return this;
            }

            /** Gap between stacked children. */
            public Builder spacing(float value) {
                this.spacing = value;
                return this;
            }

            /** Width of a category window. Fixed, so nothing measures text to lay out. */
            public Builder windowWidth(float value) {
                this.windowWidth = value;
                return this;
            }

            /** Height of a window's draggable title bar. */
            public Builder titleHeight(float value) {
                this.titleHeight = value;
                return this;
            }

            /** Indent applied to a row nested inside a group or a dropdown. */
            public Builder indent(float value) {
                this.indent = value;
                return this;
            }

            public Metrics build() {
                return new Metrics(this);
            }
        }
    }

    /** Replaces the whole grid. Call before the GUI is built, i.e. before {@code Core.start()}. */
    public static void metrics(Metrics replacement) {
        metrics = replacement == null ? Metrics.defaults() : replacement;
    }

    public static Metrics metrics() {
        return metrics;
    }

    public static float rowHeight() {
        return metrics.getRowHeight();
    }

    public static float padding() {
        return metrics.getPadding();
    }

    public static float spacing() {
        return metrics.getSpacing();
    }

    public static float windowWidth() {
        return metrics.getWindowWidth();
    }

    public static float titleHeight() {
        return metrics.getTitleHeight();
    }

    public static float indent() {
        return metrics.getIndent();
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
