package dev.px.core.gui.setting;

import dev.px.core.layout.Align;

import dev.px.core.layout.Content;

import dev.px.core.gui.Component;
import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.MouseButton;
import dev.px.core.math.MathUtil;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.util.render.ColorUtil;

/**
 * A colour picker: a saturation/brightness square, a hue strip, an alpha strip,
 * and the two dynamic modes {@link ColorSetting} already supports.
 *
 * <p>Hue, saturation and brightness are held here rather than being recovered
 * from the stored colour every frame, because that conversion is lossy at the
 * edges: black has no hue and grey has no saturation, so dragging the square
 * down to black and back up would come back red, and the picker would fight the
 * user. The row re-reads the setting only when something <em>else</em> changed
 * it.
 *
 * <p>Rainbow and theme-sync are the setting's own flags. A colour that follows
 * the theme is not a colour the picker should be editing, so the square and the
 * strips hide themselves while either mode is on &mdash; the same visibility
 * mechanism a {@code visibleWhen} setting uses.
 */
public final class ColorRow extends SettingComponent<ColorSetting> {

    private static final float SQUARE_HEIGHT = 34f;
    private static final float STRIP_HEIGHT = 6f;
    private static final float SWATCH = 8f;

    private boolean open;

    private float hue;
    private float saturation;
    private float brightness;

    /** The last value this row wrote, so an external change can be told apart from its own. */
    private Color lastWritten;

    public ColorRow(ColorSetting setting) {
        super(setting);
        readBack();

        add(new SquareArea(this));
        add(new HueStrip(this));
        add(new AlphaStrip(this));
        add(new FlagRow("Rainbow", this::isRainbow, this::toggleRainbow));
        add(new FlagRow("Sync To Theme", this::isSyncToTheme, this::toggleSyncToTheme));
    }

    @Override
    protected boolean showsChildren() {
        return open;
    }

    @Override
    protected float childIndent() {
        return GuiStyle.indent();
    }

    @Override
    protected void content(Content c) {
        readBack();
        header(c, open, row -> row.custom("swatch", SWATCH, SWATCH, (x, y, w, h) -> {
            Render.roundRect(x, y, w, h, 2f, getSetting().resolve());
            Render.roundRectOutline(x, y, w, h, 2f, 1f, GuiStyle.outline());
        }));
    }

    @Override
    protected boolean onClick(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        open = !open;
        return true;
    }

    // ------------------------------------------------------------ colour state

    /** Pulls hue/saturation/brightness back out of the setting if something else set it. */
    private void readBack() {
        Color current = getSetting().get();
        if (current.equals(lastWritten)) {
            return;
        }
        float[] hsb = ColorUtil.toHsb(current);
        hue = hsb[0];
        saturation = hsb[1];
        brightness = hsb[2];
        lastWritten = current;
    }

    private void write(Color updated) {
        getSetting().set(updated);
        // Read back what was stored, not what was offered: the setting coerces an
        // alpha it does not allow, and remembering the offer would make every
        // frame look like an external change.
        lastWritten = getSetting().get();
    }

    void setSaturationBrightness(float newSaturation, float newBrightness) {
        this.saturation = MathUtil.clamp(newSaturation, 0f, 1f);
        this.brightness = MathUtil.clamp(newBrightness, 0f, 1f);
        write(Color.hsb(hue, saturation, brightness, getSetting().get().alphaF()));
    }

    void setHue(float newHue) {
        this.hue = MathUtil.clamp(newHue, 0f, 1f);
        write(Color.hsb(hue, saturation, brightness, getSetting().get().alphaF()));
    }

    void setAlpha(float alpha) {
        write(getSetting().get().withAlpha(MathUtil.clamp(alpha, 0f, 1f)));
    }

    Color atFullBrightness() {
        return Color.hsb(hue, 1f, 1f);
    }

    float getHue() {
        return hue;
    }

    float getSaturation() {
        return saturation;
    }

    float getBrightness() {
        return brightness;
    }

    /** @return whether the picker controls are meaningful, i.e. no dynamic mode is on. */
    boolean isEditable() {
        return !getSetting().isRainbow() && !getSetting().isSyncToTheme();
    }

    private boolean isRainbow() {
        return getSetting().isRainbow();
    }

    private void toggleRainbow() {
        getSetting().rainbow(!getSetting().isRainbow());
    }

    private boolean isSyncToTheme() {
        return getSetting().isSyncToTheme();
    }

    private void toggleSyncToTheme() {
        getSetting().syncToTheme(!getSetting().isSyncToTheme());
    }

    // ------------------------------------------------------------- sub-areas

    /** Shared behaviour of the three draggable areas: press grabs, drag follows. */
    private abstract static class PickerArea extends Component {

        final ColorRow owner;

        PickerArea(ColorRow owner) {
            this.owner = owner;
        }

        @Override
        public boolean isVisible() {
            return owner.isEditable();
        }

        @Override
        protected boolean onClick(float x, float y, MouseButton button) {
            if (button != MouseButton.LEFT) {
                return false;
            }
            Screen screen = getScreen();
            if (screen != null) {
                screen.beginDrag(this);
            }
            onDrag(x, y);
            return true;
        }

        /** @return where a position sits across this area, 0..1. */
        final float across(float pointerX) {
            float width = getBounds().getWidth();
            return width <= 0f ? 0f : MathUtil.clamp((pointerX - getBounds().getX()) / width, 0f, 1f);
        }

        final float down(float pointerY) {
            float height = getBounds().getHeight();
            return height <= 0f ? 0f : MathUtil.clamp((pointerY - getBounds().getY()) / height, 0f, 1f);
        }
    }

    /** Saturation left to right, brightness top to bottom. */
    private static final class SquareArea extends PickerArea {

        SquareArea(ColorRow owner) {
            super(owner);
        }

        @Override
        protected void content(Content c) {
            c.align(Align.STRETCH);
            c.custom("area", 0f, SQUARE_HEIGHT, (x, y, w, h) -> {
                // White to the hue across, then transparent to black down. Two
                // gradients give the whole square without the backend needing a shader.
                Render.gradientH(x, y, w, h, Color.WHITE, owner.atFullBrightness());
                Render.gradientV(x, y, w, h, Color.of(0, 0, 0, 0), Color.BLACK);

                float markerX = x + w * owner.getSaturation();
                float markerY = y + h * (1f - owner.getBrightness());
                Render.circleOutline(markerX, markerY, 2.5f, 1f, Color.WHITE);
            });
        }

        @Override
        protected void onDrag(float x, float y) {
            owner.setSaturationBrightness(across(x), 1f - down(y));
        }
    }

    /** The hue ramp, drawn as six linear segments around the colour wheel. */
    private static final class HueStrip extends PickerArea {

        private static final int SEGMENTS = 6;

        HueStrip(ColorRow owner) {
            super(owner);
        }

        @Override
        protected void content(Content c) {
            c.align(Align.STRETCH);
            c.custom("area", 0f, STRIP_HEIGHT, (x, y, w, h) -> {
                float step = w / SEGMENTS;
                for (int i = 0; i < SEGMENTS; i++) {
                    Render.gradientH(x + step * i, y, step, h,
                            Color.hsb(i / (float) SEGMENTS, 1f, 1f),
                            Color.hsb((i + 1) / (float) SEGMENTS, 1f, 1f));
                }
                float markerX = x + w * owner.getHue();
                Render.rect(markerX - 1f, y, 2f, h, Color.WHITE);
            });
        }

        @Override
        protected void onDrag(float x, float y) {
            owner.setHue(across(x));
        }
    }

    /** Transparent to opaque, and absent entirely on a setting that forbids alpha. */
    private static final class AlphaStrip extends PickerArea {

        AlphaStrip(ColorRow owner) {
            super(owner);
        }

        @Override
        public boolean isVisible() {
            return super.isVisible() && owner.getSetting().isAllowAlpha();
        }

        @Override
        protected void content(Content c) {
            c.align(Align.STRETCH);
            c.custom("area", 0f, STRIP_HEIGHT, (x, y, w, h) -> {
                Color solid = owner.getSetting().get().withAlpha(255);
                Render.gradientH(x, y, w, h, solid.withAlpha(0), solid);
                float markerX = x + w * owner.getSetting().get().alphaF();
                Render.rect(markerX - 1f, y, 2f, h, Color.WHITE);
            });
        }

        @Override
        protected void onDrag(float x, float y) {
            owner.setAlpha(across(x));
        }
    }

    /**
     * A checkbox for one of the setting's dynamic modes.
     *
     * <p>Not a {@link BooleanRow}: rainbow and theme-sync are flags on the colour
     * setting itself, not settings of their own, so there is nothing to hand a
     * {@code BooleanSetting}-shaped row.
     */
    private static final class FlagRow extends Component {

        private static final float BOX = 5f;

        private final String label;
        private final java.util.function.BooleanSupplier state;
        private final Runnable toggle;

        FlagRow(String label, java.util.function.BooleanSupplier state, Runnable toggle) {
            this.label = label;
            this.state = state;
            this.toggle = toggle;
        }

        @Override
        protected void content(Content c) {
            boolean on = state.getAsBoolean();
            c.height(GuiStyle.rowHeight()).align(Align.CENTER);
            c.row(r -> {
                r.gap(GuiStyle.padding()).align(Align.CENTER);
                r.roundRect(BOX, BOX, 1f, on ? GuiStyle.accent() : GuiStyle.outline());
                r.text(label, on ? GuiStyle.text() : GuiStyle.textMuted());
            });
        }

        @Override
        protected boolean onClick(float x, float y, MouseButton button) {
            if (button != MouseButton.LEFT) {
                return false;
            }
            toggle.run();
            return true;
        }
    }
}
