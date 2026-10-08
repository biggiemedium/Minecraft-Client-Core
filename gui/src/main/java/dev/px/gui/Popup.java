package dev.px.gui;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Size;
import dev.px.core.util.Validate;

/**
 * Where a popup opens: beside a widget, at a point, centred, or over the whole
 * screen.
 *
 * <pre>{@code
 * screen.popup(choices, Popup.below(dropdown).matchWidth());      // a dropdown
 * screen.popup(menu, Popup.at(mouseX, mouseY));                   // a context menu
 * screen.popup(dialog, Popup.fill().onClose(this::cancelled));    // a modal
 * }</pre>
 *
 * <p>A popup is laid out at the size it wants, or at its anchor's width with
 * {@link #matchWidth()}, and kept on the screen. Beside an anchor it opens on
 * the side asked for if it fits, flips to the other side when that has more
 * room, and is cut to the room on the side it opened on; a {@code Scroll}
 * inside it that {@linkplain Widget#grow grows} then scrolls rather than
 * running off the screen. At a point it opens down and to the right of it,
 * flipping as needed. Centred and filling popups are capped to the screen.
 *
 * <p>A popup filling the screen is a modal: it covers everything, so nothing
 * beneath it can be hit while it is open. Its renderer draws any dimming.
 *
 * <p>Immutable: each method returns a new placement.
 */
public final class Popup {

    private enum Kind { BELOW, ABOVE, RIGHT, LEFT, POINT, CENTRED, FILL }

    private final Kind kind;
    private final Widget anchor;
    private final float x;
    private final float y;
    private final float gap;
    private final boolean matchWidth;
    private final Runnable onClose;

    private Popup(Kind kind, Widget anchor, float x, float y, float gap, boolean matchWidth, Runnable onClose) {
        this.kind = kind;
        this.anchor = anchor;
        this.x = x;
        this.y = y;
        this.gap = gap;
        this.matchWidth = matchWidth;
        this.onClose = onClose;
    }

    // ------------------------------------------------------------ factories

    /** Below a widget, flipping above it when there is more room there. */
    public static Popup below(Widget anchor) {
        return beside(Kind.BELOW, anchor);
    }

    /** Above a widget, flipping below it when there is more room there. */
    public static Popup above(Widget anchor) {
        return beside(Kind.ABOVE, anchor);
    }

    /** To the right of a widget, flipping to its left when there is more room there. A submenu. */
    public static Popup rightOf(Widget anchor) {
        return beside(Kind.RIGHT, anchor);
    }

    /** To the left of a widget, flipping to its right when there is more room there. */
    public static Popup leftOf(Widget anchor) {
        return beside(Kind.LEFT, anchor);
    }

    /** With its corner at a point, opening down and right and flipping to stay on screen. A context menu. */
    public static Popup at(float x, float y) {
        return new Popup(Kind.POINT, null, x, y, 0f, false, null);
    }

    /** In the middle of the screen. */
    public static Popup centred() {
        return new Popup(Kind.CENTRED, null, 0f, 0f, 0f, false, null);
    }

    /** Over the whole screen: a modal, since nothing beneath it can be hit. */
    public static Popup fill() {
        return new Popup(Kind.FILL, null, 0f, 0f, 0f, false, null);
    }

    private static Popup beside(Kind kind, Widget anchor) {
        return new Popup(kind, Validate.notNull(anchor, "anchor"), 0f, 0f, 0f, false, null);
    }

    // -------------------------------------------------------------- options

    /** Space between the popup and its anchor. Zero unless set. */
    public Popup gap(float spacing) {
        Validate.check(spacing >= 0f, "a popup's gap cannot be negative");
        return new Popup(kind, anchor, x, y, spacing, matchWidth, onClose);
    }

    /** Makes the popup as wide as its anchor, as a dropdown's list usually is. */
    public Popup matchWidth() {
        Validate.check(anchor != null, "only a popup beside a widget can match its width");
        return new Popup(kind, anchor, x, y, gap, true, onClose);
    }

    /** Runs {@code listener} when the popup closes, however it was closed. */
    public Popup onClose(Runnable listener) {
        return new Popup(kind, anchor, x, y, gap, matchWidth, Validate.notNull(listener, "listener"));
    }

    /** @return the widget this popup opens beside, or null. */
    public Widget getAnchor() {
        return anchor;
    }

    void closed() {
        if (onClose != null) {
            onClose.run();
        }
    }

    // ---------------------------------------------------------------- engine

    /** Where a popup goes on a screen of this size, its anchor already laid out. */
    Bounds place(Widget popup, float screenWidth, float screenHeight) {
        switch (kind) {
            case FILL:
                return Bounds.of(0f, 0f, screenWidth, screenHeight);
            case CENTRED: {
                Size size = sized(popup, Float.NaN, screenWidth, screenHeight);
                return Bounds.of((screenWidth - size.getWidth()) / 2f, (screenHeight - size.getHeight()) / 2f,
                        size.getWidth(), size.getHeight());
            }
            case POINT: {
                Size size = sized(popup, Float.NaN, screenWidth, screenHeight);
                float left = x + size.getWidth() > screenWidth ? x - size.getWidth() : x;
                float top = y + size.getHeight() > screenHeight ? y - size.getHeight() : y;
                return Bounds.of(clamp(left, screenWidth - size.getWidth()), clamp(top, screenHeight - size.getHeight()),
                        size.getWidth(), size.getHeight());
            }
            case BELOW:
            case ABOVE:
                return vertical(popup, screenWidth, screenHeight);
            default:
                return horizontal(popup, screenWidth, screenHeight);
        }
    }

    private Bounds vertical(Widget popup, float screenWidth, float screenHeight) {
        Bounds at = anchor.getBounds();
        Size size = sized(popup, matchWidth ? at.getWidth() : Float.NaN, screenWidth, screenHeight);
        float below = screenHeight - at.getBottom() - gap;
        float above = at.getY() - gap;
        boolean down = kind == Kind.BELOW
                ? size.getHeight() <= below || below >= above
                : !(size.getHeight() <= above || above >= below);
        float height = Math.min(size.getHeight(), Math.max(0f, down ? below : above));
        float top = down ? at.getBottom() + gap : at.getY() - gap - height;
        return Bounds.of(clamp(at.getX(), screenWidth - size.getWidth()), top, size.getWidth(), height);
    }

    private Bounds horizontal(Widget popup, float screenWidth, float screenHeight) {
        Bounds at = anchor.getBounds();
        Size size = sized(popup, matchWidth ? at.getWidth() : Float.NaN, screenWidth, screenHeight);
        float right = screenWidth - at.getRight() - gap;
        float left = at.getX() - gap;
        boolean rightwards = kind == Kind.RIGHT
                ? size.getWidth() <= right || right >= left
                : !(size.getWidth() <= left || left >= right);
        float width = Math.min(size.getWidth(), Math.max(0f, rightwards ? right : left));
        float side = rightwards ? at.getRight() + gap : at.getX() - gap - width;
        return Bounds.of(side, clamp(at.getY(), screenHeight - size.getHeight()), width, size.getHeight());
    }

    /**
     * The popup's size: at {@code width} if given, otherwise as wide as it
     * wants, no larger than the screen either way.
     */
    private static Size sized(Widget popup, float width, float screenWidth, float screenHeight) {
        float w = Float.isNaN(width) ? popup.naturalSize().getWidth() : width;
        w = Math.min(w, screenWidth);
        return Size.of(w, Math.min(popup.heightAt(w), screenHeight));
    }

    private static float clamp(float position, float furthest) {
        return Math.max(0f, Math.min(position, furthest));
    }
}
