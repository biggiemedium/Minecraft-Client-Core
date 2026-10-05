package dev.px.core.render;

import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;

/**
 * The world-space drawing backend.
 *
 * <p>Separate from {@link Render2D} because the two have genuinely different
 * implementers. A 2D vector library such as NanoVG or Skija has no concept of a
 * camera, a depth buffer, or a sphere in world space, so it can never implement
 * these. This is supplied by the version adapter, which is the only code that
 * knows how the game sets up its projection.
 *
 * <p>All positions are absolute world coordinates. The backend subtracts the
 * interpolated camera position itself, so callers never deal with render offsets.
 */
public interface Render3D {

    /**
     * Opens a world-space frame.
     *
     * @param partialTicks interpolation between the last two ticks, so positions
     *        can be smoothed rather than snapping at 20Hz
     */
    void beginFrame(float partialTicks);

    void endFrame();

    // -------------------------------------------------------------- shapes

    void box(Box box, Color color);

    void boxOutline(Box box, float thickness, Color color);

    /** A filled box with a stroked edge, in one call so the two cannot disagree. */
    void boxFilledOutline(Box box, Color fill, Color outline, float thickness);

    void line(Vec3 from, Vec3 to, float thickness, Color color);

    /**
     * A UV sphere.
     *
     * @param segments latitude and longitude divisions; 16 is smooth enough at
     *        typical distances and costs a fraction of a higher count
     */
    void sphere(Vec3 center, float radius, int segments, Color color);

    void sphereOutline(Vec3 center, float radius, int segments, float thickness, Color color);

    /** A horizontal disc, the shape used for range indicators and target circles. */
    void circle(Vec3 center, float radius, int segments, Color color);

    void circleOutline(Vec3 center, float radius, int segments, float thickness, Color color);

    /** A vertical cylinder, for beams and pillars. */
    void cylinder(Vec3 base, float radius, float height, int segments, Color color);

    /** A line from the edge of the screen to a world position. */
    void tracer(Vec3 target, float thickness, Color color);

    // ------------------------------------------------------------ projection

    /**
     * Projects a world position onto the screen.
     *
     * @return screen coordinates in the same scaled space {@link Render2D} uses,
     *         or {@code null} when the point is behind the camera. Returning null
     *         rather than an off-screen coordinate is deliberate: a projected
     *         point behind the viewer comes back mirrored, which is how nametags
     *         end up drawn on the wrong side of the screen.
     */
    Vec2 worldToScreen(Vec3 position);

    /** @return the screen-space bounds of a world box, or {@code null} if fully behind the camera. */
    ScreenBounds project(Box box);

    /** @return the interpolated camera position this frame. */
    Vec3 getCameraPosition();

    // ----------------------------------------------------------------- state

    /**
     * Whether subsequent draws respect the depth buffer.
     *
     * <p>Off is how a box is drawn through walls; leaving it off by accident is
     * why ESP sometimes renders over the GUI, so the backend must restore the
     * previous value in {@link #endFrame()}.
     */
    void setDepthTest(boolean enabled);

    /** The projected screen-space rectangle of a world box. */
    final class ScreenBounds {

        public final float minX;
        public final float minY;
        public final float maxX;
        public final float maxY;

        public ScreenBounds(float minX, float minY, float maxX, float maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        public float getWidth() {
            return maxX - minX;
        }

        public float getHeight() {
            return maxY - minY;
        }

        public float getCenterX() {
            return (minX + maxX) / 2f;
        }
    }
}
