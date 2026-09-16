package dev.px.core.test.suite;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.test.harness.Checks;
import dev.px.core.util.math.Curves;
import dev.px.core.util.math.Statistics;

import java.util.Arrays;
import java.util.List;

/**
 * The grid value types and the curve and statistics helpers built on them.
 *
 * <p>{@link Vec3i} and {@link Direction} are the foundation the spatial
 * algorithms stand on, so their edge cases are worth more attention than they
 * look: a floor that truncates or a packing that loses a sign is a bug that
 * surfaces a long way from here, as a pathfinder that works everywhere except
 * west of the origin.
 */
public final class MathTests {

    private MathTests() {
    }

    public static void run() {
        gridPositions();
        directions();
        curves();
        statistics();
    }

    private static void gridPositions() {
        Checks.section("Grid positions");

        Checks.checkEquals("a position floors into a cell", Vec3i.of(3, 64, 7),
                Vec3i.floorOf(Vec3.of(3.9d, 64.2d, 7.5d)));
        // Truncation would answer 0 here, putting everything just west or north of
        // the origin one cell out.
        Checks.checkEquals("and floors negatives downwards, not towards zero", Vec3i.of(-1, -1, -1),
                Vec3i.floorOf(Vec3.of(-0.5d, -0.1d, -0.9d)));
        Checks.checkEquals("an exact coordinate is its own cell", Vec3i.of(-2, 0, 5),
                Vec3i.floorOf(Vec3.of(-2d, 0d, 5d)));

        Vec3i cell = Vec3i.of(10, 64, -20);
        Checks.checkEquals("a cell's corner is its coordinates", Vec3.of(10d, 64d, -20d), cell.toVec3());
        Checks.checkEquals("its centre is half a block in", Vec3.of(10.5d, 64.5d, -19.5d), cell.center());
        Checks.checkEquals("its box is a unit cube", 1f, (float) cell.toBox().getWidth());
        Checks.check("and the centre is inside it", cell.toBox().contains(cell.center()));

        Checks.checkEquals("stepping up", Vec3i.of(10, 65, -20), cell.up());
        Checks.checkEquals("stepping along a direction", Vec3i.of(11, 64, -20), cell.offset(Direction.EAST));
        Checks.checkEquals("several steps at once", Vec3i.of(10, 64, -23),
                cell.offset(Direction.NORTH, 3));
        Checks.checkEquals("subtracting is the inverse of adding", cell,
                cell.add(4, -2, 9).subtract(Vec3i.of(4, -2, 9)));

        Checks.checkEquals("manhattan distance counts steps", 6,
                Vec3i.ZERO.manhattanDistanceTo(Vec3i.of(1, 2, 3)));
        Checks.checkEquals("squared distance skips the root", 14,
                Vec3i.ZERO.squaredDistanceTo(Vec3i.of(1, 2, 3)));
        Checks.check("neighbours sharing a face are adjacent", Vec3i.ZERO.isAdjacentTo(Vec3i.of(0, 1, 0)));
        Checks.check("diagonals are not", !Vec3i.ZERO.isAdjacentTo(Vec3i.of(1, 1, 0)));

        // ---- packing -----------------------------------------------------------
        Vec3i[] samples = {
                Vec3i.ZERO,
                Vec3i.of(1, 2, 3),
                Vec3i.of(-1, -1, -1),
                Vec3i.of(-3000, 200, 4500),
                Vec3i.of(30_000_000, -2048, -30_000_000),
                Vec3i.of(-33_554_431, 2047, 33_554_431)
        };
        boolean packed = true;
        for (Vec3i sample : samples) {
            packed &= sample.equals(Vec3i.fromLong(sample.asLong()));
        }
        Checks.check("packing round-trips across the whole world range", packed);
        Checks.check("distinct cells pack to distinct keys",
                Vec3i.of(1, 0, 0).asLong() != Vec3i.of(0, 0, 1).asLong());
        Checks.checkEquals("equal cells are equal keys", (Object) Vec3i.of(5, 6, 7).asLong(),
                (Object) Vec3i.asLong(5, 6, 7));
    }

    private static void directions() {
        Checks.section("Directions");

        Checks.checkEquals("north is negative Z", -1, Direction.NORTH.getOffsetZ());
        Checks.checkEquals("opposites reverse", Direction.WEST, Direction.EAST.opposite());
        Checks.checkEquals("every direction reverses twice to itself", Direction.UP,
                Direction.UP.opposite().opposite());
        Checks.check("the compass is horizontal", Direction.SOUTH.isHorizontal());
        Checks.check("up is not", !Direction.UP.isHorizontal());
        Checks.checkEquals("directions know their axis", Direction.Axis.Z, Direction.NORTH.getAxis());
        Checks.checkEquals("a direction is a unit vector", 1f,
                (float) Direction.EAST.toVector().length());

        // Yaw 0 faces south in the game's convention, and this has to agree with
        // the look vector maths or aiming at a block face points the wrong way.
        Checks.checkEquals("yaw 0 faces south", Direction.SOUTH, Direction.fromYaw(0f));
        Checks.checkEquals("yaw 90 faces west", Direction.WEST, Direction.fromYaw(90f));
        Checks.checkEquals("yaw wraps", Direction.NORTH, Direction.fromYaw(-180f));
        Checks.checkEquals("and rounds to the nearest", Direction.EAST, Direction.fromYaw(-80f));
        boolean roundTrips = true;
        for (Direction direction : Direction.horizontals()) {
            roundTrips &= Direction.fromYaw(direction.toYaw()) == direction;
        }
        Checks.check("every compass direction round-trips through its yaw", roundTrips);

        Checks.checkEquals("the nearest direction to a look vector", Direction.UP,
                Direction.nearest(Vec3.of(0.1d, 0.9d, 0.2d)));
        Checks.checkEquals("ties on the horizontal go to the larger axis", Direction.WEST,
                Direction.nearest(Vec3.of(-0.8d, 0.1d, 0.3d)));
        Checks.checkEquals("a zero vector has no direction to pick, so up", Direction.UP,
                Direction.nearest(Vec3.ZERO));
        Checks.checkEquals("the step between adjacent cells", Direction.UP,
                Direction.between(Vec3i.ZERO, Vec3i.of(0, 1, 0)));
        Checks.checkEquals("non-adjacent cells have no single direction", null,
                Direction.between(Vec3i.ZERO, Vec3i.of(1, 1, 0)));
    }

    private static void curves() {
        Checks.section("Curves");

        Vec3 start = Vec3.of(0d, 0d, 0d);
        Vec3 end = Vec3.of(10d, 0d, 0d);
        Vec3 control = Vec3.of(5d, 10d, 0d);

        Checks.checkEquals("a bezier starts at its first point", start,
                Curves.quadratic(start, control, end, 0d));
        Checks.checkEquals("and ends at its last", end, Curves.quadratic(start, control, end, 1d));
        Checks.check("it bends towards the control point",
                Curves.quadratic(start, control, end, 0.5d).getY() > 0d);
        Checks.check("but never reaches it",
                Curves.quadratic(start, control, end, 0.5d).getY() < control.getY());
        Checks.checkEquals("progress is clamped", end, Curves.quadratic(start, control, end, 4d));
        Checks.checkEquals("a cubic bezier ends where it should", end,
                Curves.cubic(start, control, control, end, 1d));
        Checks.checkEquals("the screen-space form works the same", Vec2.of(10d, 0d),
                Curves.cubic(Vec2.ZERO, Vec2.of(5d, 9d), Vec2.of(5d, 9d), Vec2.of(10d, 0d), 1d));

        // A spline passes through its points; a bezier does not. That distinction
        // is the whole reason both are here.
        Vec3 previous = Vec3.of(-1d, 0d, 0d);
        Vec3 next = Vec3.of(11d, 0d, 0d);
        Checks.checkEquals("a spline segment starts on its point", start,
                Curves.catmullRom(previous, start, end, next, 0d));
        Checks.checkEquals("and ends on the next one", end,
                Curves.catmullRom(previous, start, end, next, 1d));

        List<Vec3> corner = Arrays.asList(
                Vec3.of(0d, 0d, 0d), Vec3.of(5d, 0d, 0d), Vec3.of(5d, 0d, 5d), Vec3.of(10d, 0d, 5d));
        List<Vec3> smoothed = Curves.smooth(corner, 8);
        Checks.checkEquals("smoothing samples every segment", 25, smoothed.size());
        Checks.checkEquals("keeping the first point", corner.get(0), smoothed.get(0));
        Checks.checkEquals("and the last", corner.get(3), smoothed.get(smoothed.size() - 1));
        Checks.check("the corner is rounded off, not squared",
                smoothed.get(9).getZ() > 0d && smoothed.get(9).getX() > 4d);
        Checks.checkEquals("two points have no corners to round", 2,
                Curves.smooth(Arrays.asList(start, end), 8).size());

        // Uneven segments: 1 long, then 9 long. Half the distance is inside the second.
        List<Vec3> uneven = Arrays.asList(Vec3.ZERO, Vec3.of(1d, 0d, 0d), Vec3.of(10d, 0d, 0d));
        Checks.checkEquals("sampling a path measures distance, not nodes", 5f,
                (float) Curves.along(uneven, 0.5d).getX());
        Checks.checkEquals("the end is the end", 10f, (float) Curves.along(uneven, 1d).getX());
        Checks.checkEquals("a single point path is that point", Vec3.ZERO,
                Curves.along(Arrays.asList(Vec3.ZERO), 0.5d));
    }

    private static void statistics() {
        Checks.section("Statistics");

        double[] samples = { 2d, 4d, 4d, 4d, 5d, 5d, 7d, 9d };
        Checks.checkEquals("the mean", 5f, (float) Statistics.mean(samples));
        Checks.checkEquals("the population variance", 4f, (float) Statistics.variance(samples));
        Checks.checkEquals("the standard deviation", 2f, (float) Statistics.standardDeviation(samples));
        Checks.checkEquals("the median of an even count sits between the two middles", 4.5f,
                (float) Statistics.median(samples));
        Checks.checkEquals("the median of an odd count is the middle", 5f,
                (float) Statistics.median(1d, 5d, 100d));
        Checks.checkEquals("a percentile reads the sorted samples", 9f,
                (float) Statistics.percentile(1d, samples));
        Checks.checkEquals("and clamps out of range", 2f, (float) Statistics.percentile(-1d, samples));
        Checks.checkEquals("min", 2f, (float) Statistics.min(samples));
        Checks.checkEquals("max", 9f, (float) Statistics.max(samples));

        // Everything here ends up on screen, so nothing may return NaN.
        Checks.checkEquals("an empty mean is zero, not NaN", 0f, (float) Statistics.mean());
        Checks.checkEquals("an empty deviation too", 0f, (float) Statistics.standardDeviation());
        Checks.checkEquals("and an empty median", 0f, (float) Statistics.median());

        // A steady value and a jumpy one can share an average; only the deviation
        // tells them apart, which is the point of having it.
        dev.px.core.util.collect.RollingAverage steady = dev.px.core.util.collect.RollingAverage.of(4);
        dev.px.core.util.collect.RollingAverage jumpy = dev.px.core.util.collect.RollingAverage.of(4);
        for (double value : new double[] { 40d, 40d, 40d, 40d }) {
            steady.push(value);
        }
        for (double value : new double[] { 10d, 70d, 10d, 70d }) {
            jumpy.push(value);
        }
        Checks.checkEquals("two windows can share an average", (float) steady.average(),
                (float) jumpy.average());
        Checks.checkEquals("the steady one has no deviation", 0f, (float) steady.standardDeviation());
        Checks.checkEquals("the jumpy one does", 30f, (float) jumpy.standardDeviation());
        Checks.checkEquals("samples come back oldest first", 10f, (float) jumpy.toArray()[0]);
    }
}
