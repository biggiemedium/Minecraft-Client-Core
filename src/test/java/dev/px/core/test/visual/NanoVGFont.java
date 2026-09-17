package dev.px.core.test.visual;

import dev.px.core.render.font.Font;
import lombok.Getter;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * A {@link Font} backed by a NanoVG face.
 *
 * <p>Measurement goes through NanoVG itself rather than being estimated, which
 * matters more than it sounds: the HUD's layout is built entirely out of
 * measured text, so if this lies, every element is the wrong size.
 */
@Getter
public final class NanoVGFont implements Font {

    private final long vg;
    private final String name;
    private final int handle;
    private final float size;

    private final float[] bounds = new float[4];

    public NanoVGFont(long vg, String name, int handle, float size) {
        this.vg = vg;
        this.name = name;
        this.handle = handle;
        this.size = size;
    }

    @Override
    public float widthOf(String text) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        nvgFontFaceId(vg, handle);
        nvgFontSize(vg, size);
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
        return nvgTextBounds(vg, 0f, 0f, text, bounds);
    }

    @Override
    public float getHeight() {
        // The cap-to-baseline box rather than the full line box, so a one-line
        // element is not padded by the font's leading.
        return size * 0.72f;
    }

    @Override
    public float getLineHeight() {
        return size * 1.2f;
    }
}
