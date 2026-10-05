package dev.px.core.gui.setting;

import dev.px.core.gui.GuiStyle;
import dev.px.core.layout.Content;
import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.MouseButton;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.NumberSetting;

/**
 * A slider.
 *
 * <p>The setting already converts between its value and a 0..1 position
 * ({@link NumberSetting#progress()} / {@link NumberSetting#setProgress}), and it
 * already clamps and snaps to its step. So this row does no arithmetic on the
 * value at all: it turns a cursor position into a fraction and hands it over.
 * Bounds and stepping stay the setting's business, which is why dragging past
 * either end of the track cannot produce an out-of-range value.
 */
public final class NumberRow extends SettingComponent<NumberSetting<?>> {

    private static final float TRACK_HEIGHT = 2f;

    public NumberRow(NumberSetting<?> setting) {
        super(setting);
    }

    @Override
    protected void content(Content c) {
        header(c, false, row -> value(row, getSetting().displayValue()));
        track(c, TRACK_HEIGHT, (x, y, w, h) -> Render.progressBar(x, y, w, h, h / 2f,
                getSetting().progress(), GuiStyle.outline(), GuiStyle.accent()));
    }

    @Override
    protected boolean onClick(float pointerX, float pointerY, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        // A press is already a drag of zero length, so the value jumps to where
        // the user clicked and then follows the cursor without a second gesture.
        Screen screen = getScreen();
        if (screen != null) {
            screen.beginDrag(this);
        }
        apply(pointerX);
        return true;
    }

    @Override
    protected void onDrag(float pointerX, float pointerY) {
        apply(pointerX);
    }

    private void apply(float pointerX) {
        // Measured against the track as drawn, not recomputed from the row.
        getSetting().setProgress(progressIn("track", pointerX));
    }
}
