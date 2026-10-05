package dev.px.core.render;

/** Horizontal anchor for text and for HUD elements that reposition on screen edges. */
public enum Alignment {

    LEFT,
    CENTER,
    RIGHT;

    /**
     * @param width the width of the content being placed
     * @return the x offset to add so the content sits correctly against an anchor
     */
    public float offsetFor(float width) {
        switch (this) {
            case CENTER: return -width / 2f;
            case RIGHT: return -width;
            default: return 0f;
        }
    }
}
