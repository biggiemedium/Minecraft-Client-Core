package dev.px.core.gui.setting;

import dev.px.core.layout.Content;

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
    protected void content(Content c) {
        header(c, false, row -> value(row, getSetting().displayValue()));
        track(c, TRACK_HEIGHT, this::drawTrack);
    }

    private void drawTrack(float x, float y, float w, float h) {
        Render.roundRect(x, y, w, h, h / 2f, GuiStyle.outline());

        float lower = progressOf(getSetting().get().getLower());
        float upper = progressOf(getSetting().get().getUpper());
        Render.roundRect(x + w * lower, y, w * (upper - lower), h, h / 2f, GuiStyle.accent());

        float centerY = y + h / 2f;
        Render.circle(x + w * lower, centerY, HANDLE_RADIUS, GuiStyle.text());
        Render.circle(x + w * upper, centerY, HANDLE_RADIUS, GuiStyle.text());
    }

    @Override
    protected boolean onClick(float pointerX, float pointerY, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        float pressed = progressIn("track", pointerX);
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
        float progress = progressIn("track", pointerX);
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
