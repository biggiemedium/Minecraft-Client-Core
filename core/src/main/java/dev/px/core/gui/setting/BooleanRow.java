package dev.px.core.gui.setting;

import dev.px.core.gui.GuiStyle;
import dev.px.core.layout.Content;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.MouseButton;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.animation.Animation;
import dev.px.core.render.animation.Easing;
import dev.px.core.setting.impl.BooleanSetting;

/**
 * A checkbox, drawn as a sliding toggle.
 *
 * <p>The knob position is an {@link Animation} rather than a boolean, so the
 * switch reads as moving between two states instead of blinking between them,
 * and takes the same 120ms at any frame rate.
 */
public final class BooleanRow extends SettingComponent<BooleanSetting> {

    private static final float TRACK_WIDTH = 14f;
    private static final float TRACK_HEIGHT = 7f;

    private final Animation knob = Animation.fade(120, Easing.QUAD_OUT);

    public BooleanRow(BooleanSetting setting) {
        super(setting);
        // Start where the setting already is, so opening the GUI does not animate
        // every toggle on screen at once.
        knob.snapTo(setting.isOn() ? 1f : 0f);
    }

    @Override
    protected void content(Content c) {
        knob.target(getSetting().isOn());
        header(c, false, row -> row.custom("switch", TRACK_WIDTH, TRACK_HEIGHT, this::drawSwitch));
    }

    private void drawSwitch(float x, float y, float w, float h) {
        float progress = knob.get();

        Color off = GuiStyle.outline();
        Render.roundRect(x, y, w, h, h / 2f, off.lerp(GuiStyle.accent(), progress));

        float radius = h / 2f - 1f;
        float travel = w - h;
        Render.circle(x + h / 2f + travel * progress, y + h / 2f, radius, GuiStyle.text());
    }

    @Override
    protected boolean onClick(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        getSetting().toggle();
        return true;
    }
}
