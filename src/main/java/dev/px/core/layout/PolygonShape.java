package dev.px.core.layout;

import dev.px.core.math.Vec2;
import dev.px.core.render.Color;
import dev.px.core.render.Render;

/**
 * An arbitrary closed polygon: radar cones, diamond markers, notched panels.
 *
 * <p>Containment is a crossing-number ray cast, which handles concave outlines
 * correctly. A convex-only test would be faster but would silently accept points
 * inside the notch of a concave shape, and this runs once per click rather than
 * once per pixel.
 */
final class PolygonShape implements Shape {

    private final Vec2[] points;

    PolygonShape(Vec2... points) {
        if (points.length < 3) {
            throw new IllegalArgumentException("A polygon needs at least 3 points, got " + points.length);
        }
        this.points = points.clone();
    }

    @Override
    public boolean contains(float x, float y) {
        boolean inside = false;
        for (int i = 0, j = points.length - 1; i < points.length; j = i++) {
            double xi = points[i].getX();
            double yi = points[i].getY();
            double xj = points[j].getX();
            double yj = points[j].getY();
            // Count edges crossing the ray cast rightwards from the point.
            boolean straddles = (yi > y) != (yj > y);
            if (straddles && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    @Override
    public Bounds bounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Vec2 point : points) {
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
        }
        return Bounds.of((float) minX, (float) minY, (float) (maxX - minX), (float) (maxY - minY));
    }

    @Override
    public void stroke(float thickness, Color color) {
        for (int i = 0; i < points.length; i++) {
            Vec2 from = points[i];
            Vec2 to = points[(i + 1) % points.length];
            Render.line(from.getFloatX(), from.getFloatY(), to.getFloatX(), to.getFloatY(), thickness, color);
        }
    }

    @Override
    public Shape scaled(float originX, float originY, float factor) {
        Vec2[] moved = new Vec2[points.length];
        for (int i = 0; i < points.length; i++) {
            moved[i] = Vec2.of(
                    originX + (points[i].getX() - originX) * factor,
                    originY + (points[i].getY() - originY) * factor);
        }
        return new PolygonShape(moved);
    }
}
