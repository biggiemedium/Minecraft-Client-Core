package dev.px.core.test.harness;

import dev.px.core.render.font.Font;

/**
 * A font that measures without drawing: six pixels a character, nine tall.
 *
 * <p>The suite installs no render backend, so {@code Render.textWidth} would
 * otherwise answer zero and every text element would measure empty. Measuring is
 * Core's half of the HUD contract and has to be exercised for real, so the
 * numbers here are fixed and arbitrary rather than absent.
 */
public final class FixedFont implements Font {

    public static final float CHAR_WIDTH = 6f;
    public static final float HEIGHT = 9f;

    @Override
    public String getName() {
        return "fixed";
    }

    @Override
    public float getSize() {
        return HEIGHT;
    }

    @Override
    public float widthOf(String text) {
        return text == null ? 0f : text.length() * CHAR_WIDTH;
    }

    @Override
    public float getHeight() {
        return HEIGHT;
    }

    @Override
    public float getLineHeight() {
        return HEIGHT + 2f;
    }
}
