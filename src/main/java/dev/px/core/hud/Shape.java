package dev.px.core.hud;

import dev.px.core.math.Vec2;
import dev.px.core.render.Color;

/**
 * The region of an element that responds to clicks, and that the editor traces
 * when it is selected.
 *
 * <p>Kept separate from {@link Bounds} because layout geometry and interaction
 * geometry are not the same thing. A circular speedometer occupies a square in
 * the layout, but clicking its corner should miss it, and its selection outline
 * should be a circle rather than a box. Simple elements make the two identical
 * by doing nothing &mdash; {@link HudElement#getShape} returns a rectangle by
 * default &mdash; but nothing forces them to match.
 *
 * <p>A shape knows how to {@link #stroke} itself. That is what lets the editor
 * draw a correct selection outline around a circle or a polygon without holding
 * a switch over shape types, and what lets a new shape work in the editor the
 * day it is written.
 *
 * <p>Shapes are immutable. {@link #scaled} returns a new shape rather than
 * mutating, so the same instance can be measured and transformed freely.
 */
public interface Shape {

    /**
     * @param x scaled screen x
     * @param y scaled screen y
     * @return whether the point is inside this shape
     */
    boolean contains(float x, float y);

    /** @return the smallest rectangle enclosing this shape. Where resize handles go. */
    Bounds bounds();

    /** Draws this shape's own outline. Called by the editor for selection and hover. */
    void stroke(float thickness, Color color);

    /**
     * Scales about an arbitrary origin.
     *
     * <p>Elements build their shape at natural size; the HUD applies the element's
     * scale factor through this, so an element never has to know its own scale.
     */
    Shape scaled(float originX, float originY, float factor);

    // ------------------------------------------------------------ factories

    static Shape rect(float x, float y, float width, float height) {
        return new RectShape(Bounds.of(x, y, width, height));
    }

    static Shape rect(Bounds bounds) {
        return new RectShape(bounds);
    }

    static Shape roundRect(float x, float y, float width, float height, float radius) {
        return new RoundRectShape(Bounds.of(x, y, width, height), radius);
    }

    static Shape roundRect(Bounds bounds, float radius) {
        return new RoundRectShape(bounds, radius);
    }

    static Shape circle(float centerX, float centerY, float radius) {
        return new CircleShape(centerX, centerY, radius);
    }

    /**
     * An arbitrary closed polygon. Points are in scaled screen space and need not
     * be convex; containment uses a ray cast, so concave shapes work.
     */
    static Shape polygon(Vec2... points) {
        return new PolygonShape(points);
    }
}
