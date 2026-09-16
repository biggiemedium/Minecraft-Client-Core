package dev.px.core.gui.setting;

import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.MouseButton;
import dev.px.core.math.Range;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.RangeSetting;

/**
 * A two-handled slider.
 *
 * <p>Which handle a press grabs is decided once, by whichever is nearer, and
 * then held for the whole drag. Re-deciding every frame would make the handles
 * swap under the cursor as one is dragged past the other, and
 * {@link Range#of} normalises the bounds anyway, so the crossing is legal &mdash;
 * it just must not change which handle is being moved.
 */
public final class RangeRow extends SettingComponent<RangeSetting> {

    private static final float TRACK_HEIGHT = 2f;
    private static final float HANDLE_RADIUS = 2.5f;

    /** Which end this drag is moving. Fixed at the press. */
    private boolean draggingUpper;

    public RangeRow(RangeSetting setting) {
        super(setting);
    }

    @Override
    public void render(float x, float y, float w, float h) {
        renderRow(x, y, w, false);
        renderLabel(x, y);
        renderValue(getSetting().displayValue(), x, y, w);

        float trackX = x + GuiStyle.PADDING;
        float trackWidth = Math.max(0f, w - GuiStyle.PADDING * 2f);
        float trackY = y + GuiStyle.ROW_HEIGHT - TRACK_HEIGHT - 1f;

        Render.roundRect(trackX, trackY, trackWidth, TRACK_HEIGHT, TRACK_HEIGHT / 2f, GuiStyle.outline());

        float lower = progressOf(getSetting().get().getLower());
        float upper = progressOf(getSetting().get().getUpper());
        Render.roundRect(trackX + trackWidth * lower, trackY, trackWidth * (upper - lower),
                TRACK_HEIGHT, TRACK_HEIGHT / 2f, GuiStyle.accent());

        float centerY = trackY + TRACK_HEIGHT / 2f;
        Render.circle(trackX + trackWidth * lower, centerY, HANDLE_RADIUS, GuiStyle.text());
        Render.circle(trackX + trackWidth * upper, centerY, HANDLE_RADIUS, GuiStyle.text());
    }

    @Override
    protected boolean onClick(float pointerX, float pointerY, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        float pressed = progressAt(pointerX, getBounds().getX(), getBounds().getWidth());
        float lower = progressOf(getSetting().get().getLower());
        float upper = progressOf(getSetting().get().getUpper());
        draggingUpper = Math.abs(pressed - upper) <= Math.abs(pressed - lower);

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
        float progress = progressAt(pointerX, getBounds().getX(), getBounds().getWidth());
        double value = getSetting().getMin() + (getSetting().getMax() - getSetting().getMin()) * progress;
        if (draggingUpper) {
            getSetting().setUpper(value);
        } else {
            getSetting().setLower(value);
        }
    }

    /** @return where a value sits between the setting's limits, 0..1. */
    private float progressOf(double value) {
        double span = getSetting().getMax() - getSetting().getMin();
        return span == 0d ? 0f : (float) ((value - getSetting().getMin()) / span);
    }
}
