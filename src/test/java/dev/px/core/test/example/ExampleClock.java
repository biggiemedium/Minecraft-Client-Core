package dev.px.core.test.example;

import dev.px.core.hud.AbstractHudElement;
import dev.px.core.hud.Anchor;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudLayout;
import dev.px.core.layout.Shape;
import dev.px.core.render.Color;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import lombok.Getter;

/**
 * A HUD element with settings and a rounded, non-rectangular hit region.
 *
 * <p>Shows four things a real element needs:
 *
 * <ul>
 *   <li><b>Settings by declaration.</b> Extending {@link AbstractHudElement}
 *       brings in {@code SettingHolder}, so the fields below register and
 *       persist themselves alongside the layout.</li>
 *   <li><b>A size that changes every frame.</b> The text is wider with seconds
 *       on than off, and the anchor formula moves the element the right way when
 *       it changes. Nothing here mentions a size: it falls out of the text.</li>
 *   <li><b>One description.</b> The padding below is written once. Under the old
 *       contract it appeared in a measure method and again in a draw method, and
 *       changing one without the other is how a HUD element ends up with its
 *       background not matching its text.</li>
 *   <li><b>A frozen sample while editing.</b> {@code isEditing()} keeps the box
 *       from resizing under the cursor as the clock ticks.</li>
 * </ul>
 */
@Getter
public final class ExampleClock extends AbstractHudElement {

    private static final float PADDING = 4f;
    private static final float RADIUS = 3f;

    private final BooleanSetting showSeconds = bool("Show Seconds", true);
    private final ColorSetting colour = color("Colour", Color.WHITE);

    /** Stands in for the real clock, so the suite can control what is shown. */
    private String liveText = "13:45";

    /** Observed while describing itself, so the suite can prove the editing flag arrives. */
    private boolean sawEditing;

    public ExampleClock() {
        super("clock", "Clock", HudLayout.at(Anchor.TOP_RIGHT, -4f, 4f));
    }

    public void setLiveText(String text) {
        this.liveText = text;
    }

    @Override
    public void content(Content c) {
        sawEditing = isEditing();
        c.background(Color.of(0, 0, 0, 120), RADIUS).padding(PADDING);
        c.min(0f, 9f + PADDING * 2f);
        c.text(text(), colour.resolve());
    }

    /**
     * The drawn shape is a rounded rectangle, so the hit region should be too.
     * Clicking the very corner of a rounded panel ought to miss it.
     */
    @Override
    public Shape getShape(float x, float y, float w, float h) {
        return Shape.roundRect(x, y, w, h, RADIUS);
    }

    public String text() {
        // A frozen sample while positioning, so the element does not resize
        // under the cursor as the real clock advances.
        if (isEditing()) {
            return showSeconds.isOn() ? "12:00:00" : "12:00";
        }
        return liveText;
    }
}
