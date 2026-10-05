package dev.px.core.render.theme;

import dev.px.core.registry.Named;
import dev.px.core.render.Color;
import lombok.Getter;

/**
 * A named colour scheme.
 *
 * <p>Generalises the old {@code AccentColor}, which held two colours and left
 * every call site to decide what a background, an outline, or muted text should
 * be. Those are derived here, so a new theme is two colours and the whole GUI
 * follows, and a client that wants full control can override the derived roles.
 */
@Getter
public final class Theme implements Named {

    private final String name;

    /** The dominant accent. Enabled toggles, selected tabs, highlighted text. */
    private final Color primary;

    /** The second accent, used as the far end of every gradient. */
    private final Color secondary;

    private final Color background;
    private final Color surface;
    private final Color text;
    private final Color textMuted;
    private final Color outline;

    private Theme(Builder builder) {
        this.name = builder.name;
        this.primary = builder.primary;
        this.secondary = builder.secondary;
        this.background = builder.background != null ? builder.background : Color.of(14, 14, 18, 235);
        this.surface = builder.surface != null ? builder.surface : Color.of(24, 24, 30, 235);
        this.text = builder.text != null ? builder.text : Color.WHITE;
        this.textMuted = builder.textMuted != null ? builder.textMuted : Color.of(150, 150, 160);
        this.outline = builder.outline != null ? builder.outline : Color.of(255, 255, 255, 30);
    }

    /** The common case: a theme is a name and two accent colours. */
    public static Theme of(String name, Color primary, Color secondary) {
        return builder(name, primary, secondary).build();
    }

    public static Builder builder(String name, Color primary, Color secondary) {
        return new Builder(name, primary, secondary);
    }

    /**
     * @param progress 0 at {@link #primary}, 1 at {@link #secondary}
     * @return the accent colour that far along the gradient
     */
    public Color accent(float progress) {
        return primary.lerp(secondary, progress);
    }

    /**
     * The accent at a point in a continuously cycling gradient.
     *
     * <p>Ping-pongs rather than wrapping, so the colour never jumps back to the
     * start. Used for animated element backgrounds.
     *
     * @param cyclesPerSecond full sweeps per second
     * @param offset phase shift, so adjacent elements are not all the same colour
     */
    public Color animatedAccent(float cyclesPerSecond, float offset) {
        float period = 1000f / Math.max(cyclesPerSecond, 0.001f);
        float phase = ((System.currentTimeMillis() + (long) (offset * period)) % (long) period) / period;
        return accent(phase < 0.5f ? phase * 2f : (1f - phase) * 2f);
    }

    public static final class Builder {

        private final String name;
        private final Color primary;
        private final Color secondary;

        private Color background;
        private Color surface;
        private Color text;
        private Color textMuted;
        private Color outline;

        private Builder(String name, Color primary, Color secondary) {
            this.name = name;
            this.primary = primary;
            this.secondary = secondary;
        }

        public Builder background(Color color) {
            this.background = color;
            return this;
        }

        public Builder surface(Color color) {
            this.surface = color;
            return this;
        }

        public Builder text(Color color) {
            this.text = color;
            return this;
        }

        public Builder textMuted(Color color) {
            this.textMuted = color;
            return this;
        }

        public Builder outline(Color color) {
            this.outline = color;
            return this;
        }

        public Theme build() {
            return new Theme(this);
        }
    }
}
