package dev.px.core.util.math;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.List;

/**
 * Curves through points: Bézier for shapes that are designed, Catmull-Rom for
 * paths that are given.
 *
 * <p>{@link dev.px.core.render.animation.Easing} shapes one value over time and is
 * the right tool for a fade. It cannot describe motion through space, which needs
 * a position at every point of the journey rather than a progress value.
 *
 * <p>The difference between the two families here is who chooses the shape. A
 * Bézier is steered by control points that the curve does not touch &mdash; the
 * right tool when the shape is being designed, as in a notification sliding in
 * along an arc. A Catmull-Rom spline passes through every point it is given,
 * which is the right tool when the points are data: the output of
 * {@link dev.px.core.util.spatial.AStar}, a series of waypoints, a recorded
 * camera path. Feeding path nodes to a Bézier would produce a curve that
 * politely avoids every cell the pathfinder proved was safe.
 *
 * <p><b>Complexity.</b> A single sample is O(1); {@link #smooth} is
 * O(n · samplesPerSegment) and {@link #along} is O(n).
 *
 * <pre>{@code
 * List<Vec3> smooth = Curves.smooth(path.toPositions(), 8);
 * Vec2 slide = Curves.cubic(offScreen, control, control, resting, animation.get());
 * }</pre>
 */
public final class Curves {

    private Curves() {
    }

    // -------------------------------------------------------------- Bézier

    /**
     * @param progress position along the curve, 0..1
     *
     * <pre>
     * B(t) = (1-t)²·P₀ + 2(1-t)t·P₁ + t²·P₂
     * </pre>
     */
    public static Vec3 quadratic(Vec3 start, Vec3 control, Vec3 end, double progress) {
        double t = clamp(progress);
        double inverse = 1d - t;
        return start.scale(inverse * inverse)
                .add(control.scale(2d * inverse * t))
                .add(end.scale(t * t));
    }

    /**
     * <pre>
     * B(t) = (1-t)³·P₀ + 3(1-t)²t·P₁ + 3(1-t)t²·P₂ + t³·P₃
     * </pre>
     */
    public static Vec3 cubic(Vec3 start, Vec3 firstControl, Vec3 secondControl, Vec3 end, double progress) {
        double t = clamp(progress);
        double inverse = 1d - t;
        return start.scale(inverse * inverse * inverse)
                .add(firstControl.scale(3d * inverse * inverse * t))
                .add(secondControl.scale(3d * inverse * t * t))
                .add(end.scale(t * t * t));
    }

    /** The screen-space form, for a HUD element moving along a path rather than a line. */
    public static Vec2 quadratic(Vec2 start, Vec2 control, Vec2 end, double progress) {
        double t = clamp(progress);
        double inverse = 1d - t;
        return start.scale(inverse * inverse)
                .add(control.scale(2d * inverse * t))
                .add(end.scale(t * t));
    }

    public static Vec2 cubic(Vec2 start, Vec2 firstControl, Vec2 secondControl, Vec2 end, double progress) {
        double t = clamp(progress);
        double inverse = 1d - t;
        return start.scale(inverse * inverse * inverse)
                .add(firstControl.scale(3d * inverse * inverse * t))
                .add(secondControl.scale(3d * inverse * t * t))
                .add(end.scale(t * t * t));
    }

    // --------------------------------------------------------- Catmull-Rom

    /**
     * @param previous the point before the segment, which sets the tangent at its start
     * @param start the beginning of the segment
     * @param end the end of the segment
     * @param next the point after it, which sets the tangent at its end
     * @return the position along the segment from {@code start} to {@code end}
     *
     * <p>The uniform spline with tension &frac12;:
     *
     * <pre>
     * C(t) = &frac12;·( 2·P₁
     *            + (-P₀ + P₂)·t
     *            + (2·P₀ - 5·P₁ + 4·P₂ - P₃)·t²
     *            + (-P₀ + 3·P₁ - 3·P₂ + P₃)·t³ )
     * </pre>
     *
     * <p>The two outer points are never reached; they exist to say which way the
     * curve should be heading as it arrives and leaves. That is what makes a
     * chain of these continuous rather than a sequence of arcs meeting at corners.
     */
    public static Vec3 catmullRom(Vec3 previous, Vec3 start, Vec3 end, Vec3 next, double progress) {
        double t = clamp(progress);
        double squared = t * t;
        double cubed = squared * t;
        return previous.scale(-0.5d * cubed + squared - 0.5d * t)
                .add(start.scale(1.5d * cubed - 2.5d * squared + 1d))
                .add(end.scale(-1.5d * cubed + 2d * squared + 0.5d * t))
                .add(next.scale(0.5d * cubed - 0.5d * squared));
    }

    /**
     * Rounds off a sequence of points into a smooth path through all of them.
     *
     * <p>Written for pathfinder output. A grid path is a staircase of right angles
     * because the grid has no other way to say "roughly north-east", and something
     * following it literally stops and turns at every node. Sampling a spline
     * through the same nodes keeps the route while removing the corners.
     *
     * @param samplesPerSegment points to generate between each pair. Around 8 is
     *                          smooth enough to look continuous without producing
     *                          a list nothing can walk
     *
     * <p>O(n · samplesPerSegment), producing {@code (n-1)·samples + 1} points.
     * @return the sampled path, endpoints included. Fewer than two input points
     *         come back unchanged
     */
    public static List<Vec3> smooth(List<Vec3> points, int samplesPerSegment) {
        Validate.notNull(points, "points");
        Validate.check(samplesPerSegment > 0, "samplesPerSegment must be positive");
        if (points.size() < 3) {
            return new ArrayList<>(points);
        }
        List<Vec3> smoothed = new ArrayList<>(points.size() * samplesPerSegment);
        int last = points.size() - 1;
        for (int i = 0; i < last; i++) {
            // The ends have no point beyond them, so they stand in for their own
            // neighbour: the curve leaves and arrives along the segment itself.
            Vec3 previous = points.get(Math.max(0, i - 1));
            Vec3 start = points.get(i);
            Vec3 end = points.get(i + 1);
            Vec3 next = points.get(Math.min(last, i + 2));
            for (int sample = 0; sample < samplesPerSegment; sample++) {
                smoothed.add(catmullRom(previous, start, end, next, sample / (double) samplesPerSegment));
            }
        }
        smoothed.add(points.get(last));
        return smoothed;
    }

    /**
     * @param progress how far along the whole path, 0..1
     * @return the position there, following the straight lines between points
     *
     * <p>O(n): one pass to total the length, a second to find the segment holding
     * {@code progress · total}.
     *
     * <p>Distance-parameterised, so half way is half the length walked rather than
     * the middle node. Sampling a path by node index moves fast through the short
     * segments and slow through the long ones.
     */
    public static Vec3 along(List<Vec3> points, double progress) {
        Validate.notNull(points, "points");
        if (points.isEmpty()) {
            return Vec3.ZERO;
        }
        if (points.size() == 1) {
            return points.get(0);
        }
        double total = 0d;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i - 1).distanceTo(points.get(i));
        }
        if (total == 0d) {
            return points.get(0);
        }
        double target = clamp(progress) * total;
        double walked = 0d;
        for (int i = 1; i < points.size(); i++) {
            double segment = points.get(i - 1).distanceTo(points.get(i));
            if (walked + segment >= target) {
                double into = segment == 0d ? 0d : (target - walked) / segment;
                return points.get(i - 1).lerp(points.get(i), into);
            }
            walked += segment;
        }
        return points.get(points.size() - 1);
    }

    private static double clamp(double progress) {
        return progress < 0d ? 0d : progress > 1d ? 1d : progress;
    }
}
