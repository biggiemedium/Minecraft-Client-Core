package dev.px.core.gui.setting;

import dev.px.core.gui.GuiStyle;
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
    public void render(float x, float y, float w, float h) {
        boolean on = getSetting().isOn();
        knob.target(on);
        float progress = knob.get();

        renderRow(x, y, w, false);
        renderLabel(x, y);

        float trackX = x + w - GuiStyle.PADDING - TRACK_WIDTH;
        float trackY = y + (GuiStyle.ROW_HEIGHT - TRACK_HEIGHT) / 2f;

        Color off = GuiStyle.outline();
        Render.roundRect(trackX, trackY, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2f,
                off.lerp(GuiStyle.accent(), progress));

        float radius = TRACK_HEIGHT / 2f - 1f;
        float travel = TRACK_WIDTH - TRACK_HEIGHT;
        Render.circle(trackX + TRACK_HEIGHT / 2f + travel * progress, trackY + TRACK_HEIGHT / 2f,
                radius, GuiStyle.text());
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
