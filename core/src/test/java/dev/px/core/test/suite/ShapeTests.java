package dev.px.core.test.suite;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Shape;
import dev.px.core.layout.Size;
import dev.px.core.math.Vec2;
import dev.px.core.test.harness.Checks;

/**
 * Interaction geometry, tested as pure maths.
 *
 * <p>Separating the interaction region from the layout box is the design
 * decision the HUD system is built around, and this is where it is proved: a
 * circular element must not have a rectangular hitbox.
 *
 * <p>No client, no screen, no render backend.
 */
public final class ShapeTests {

    private ShapeTests() {
    }

    public static void run() {
        Checks.section("Shapes and geometry");

        // ---- rectangle -------------------------------------------------------
        Shape rect = Shape.rect(10f, 20f, 100f, 40f);
        Checks.check("a rectangle contains its interior", rect.contains(50f, 30f));
        Checks.check("a rectangle contains its corner", rect.contains(10f, 20f));
        Checks.check("a rectangle excludes outside", !rect.contains(9f, 30f));
        Checks.checkEquals("its bounds are itself", 100f, rect.bounds().getWidth());

        // ---- rounded rectangle ------------------------------------------------
        Shape rounded = Shape.roundRect(0f, 0f, 100f, 40f, 10f);
        Checks.check("a rounded rect contains its centre", rounded.contains(50f, 20f));
        Checks.check("and a point on a flat edge", rounded.contains(50f, 0.5f));
        Checks.check("but excludes the clipped corner", !rounded.contains(0.5f, 0.5f));
        Checks.check("and still includes just inside the arc", rounded.contains(3f, 3f + 7f));
        Checks.check("a zero radius behaves as a plain rectangle",
                Shape.roundRect(0f, 0f, 10f, 10f, 0f).contains(0f, 0f));

        // ---- circle -------------------------------------------------------------
        Shape circle = Shape.circle(100f, 100f, 20f);
        Checks.check("a circle contains its centre", circle.contains(100f, 100f));
        Checks.check("and a point just inside the rim", circle.contains(119f, 100f));
        Checks.check("but not one just outside", !circle.contains(121f, 100f));
        // The corner of the bounding box is at distance 20*sqrt(2) = 28.3 from centre.
        Checks.check("a circle excludes the corner of its own bounding box",
                !circle.contains(80f, 80f));
        Bounds circleBounds = circle.bounds();
        Checks.checkEquals("its bounds are the enclosing square", 40f, circleBounds.getWidth());
        Checks.checkEquals("positioned around the centre", 80f, circleBounds.getX());

        // ---- polygon -------------------------------------------------------------
        Shape triangle = Shape.polygon(Vec2.of(50f, 0f), Vec2.of(0f, 40f), Vec2.of(100f, 40f));
        Checks.check("a triangle contains a point inside it", triangle.contains(50f, 30f));
        Checks.check("but not the empty corner beside it", !triangle.contains(5f, 5f));
        Checks.checkEquals("its bounds enclose every vertex", 100f, triangle.bounds().getWidth());
        Checks.checkEquals("and start at the leftmost", 0f, triangle.bounds().getX());

        // A chevron: a square with a notch cut down into its top edge. A convex-only
        // containment test would wrongly accept points inside the notch.
        Shape chevron = Shape.polygon(
                Vec2.of(0f, 0f), Vec2.of(10f, 10f), Vec2.of(20f, 0f),
                Vec2.of(20f, 20f), Vec2.of(0f, 20f));
        Checks.check("a concave polygon excludes points in its notch", !chevron.contains(10f, 5f));
        Checks.check("while including the body below it", chevron.contains(10f, 15f));
        Checks.check("and the arms either side", chevron.contains(3f, 5f));

        Checks.checkThrows("a polygon needs at least three points",
                IllegalArgumentException.class,
                () -> Shape.polygon(Vec2.of(0f, 0f), Vec2.of(1f, 1f)));

        // ---- scaling --------------------------------------------------------------
        // Elements build shapes at natural size; the HUD scales them about the
        // element's top-left, so an element never accounts for its own scale.
        Shape scaledCircle = Shape.circle(20f, 20f, 10f).scaled(10f, 10f, 2f);
        Checks.checkEquals("scaling moves the centre away from the origin",
                30f, scaledCircle.bounds().getCenterX());
        Checks.checkEquals("and doubles the diameter", 40f, scaledCircle.bounds().getWidth());
        Checks.check("the enlarged circle contains a newly covered point",
                scaledCircle.contains(45f, 30f));

        Shape scaledRect = Shape.rect(10f, 10f, 20f, 20f).scaled(10f, 10f, 2f);
        Checks.checkEquals("scaling about a shape's own corner pins that corner",
                10f, scaledRect.bounds().getX());
        Checks.checkEquals("and grows away from it", 40f, scaledRect.bounds().getWidth());

        Shape scaledPolygon = triangle.scaled(0f, 40f, 0.5f);
        Checks.checkEquals("a polygon scales every vertex", 50f, scaledPolygon.bounds().getWidth());

        // ---- Bounds ------------------------------------------------------------------
        Bounds bounds = Bounds.of(10f, 10f, 30f, 20f);
        Checks.checkEquals("right edge", 40f, bounds.getRight());
        Checks.checkEquals("bottom edge", 30f, bounds.getBottom());
        Checks.checkEquals("centre", 25f, bounds.getCenterX());
        Checks.check("contains its own centre", bounds.contains(25f, 20f));
        Checks.check("intersects an overlapping rectangle",
                bounds.intersects(Bounds.of(35f, 25f, 10f, 10f)));
        Checks.check("does not intersect a separated one",
                !bounds.intersects(Bounds.of(100f, 100f, 10f, 10f)));
        Checks.checkEquals("expand grows on every side", 34f, bounds.expand(2f).getWidth());
        Checks.checkEquals("translate moves without resizing", 15f, bounds.translate(5f, 0f).getX());
        Checks.check("between accepts corners in any order",
                Bounds.between(40f, 30f, 10f, 10f).equals(bounds));
        Checks.checkEquals("negative dimensions are clamped away",
                0f, Bounds.of(0f, 0f, -5f, -5f).getWidth());

        // ---- Size ---------------------------------------------------------------------
        Checks.checkEquals("size clamps negatives too", 0f, Size.of(-1f, 5f).getWidth());
        Checks.checkEquals("padding grows both sides", 14f, Size.of(10f, 10f).padded(2f).getWidth());
        Checks.check("an empty size is reported", Size.of(0f, 10f).isEmpty());
        Checks.checkEquals("union takes the larger of each axis",
                10f, Size.of(10f, 2f).union(Size.of(4f, 8f)).getWidth());
    }
}
