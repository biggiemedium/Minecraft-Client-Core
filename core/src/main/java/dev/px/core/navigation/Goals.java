package dev.px.core.navigation;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.function.Supplier;

/** The shapes behind {@link Goal}'s factories. */
final class Goals {

    /**
     * How far below a block's floor the feet still count as in it.
     *
     * <p>Landing puts the feet on a surface by arithmetic, which can leave them a
     * hair under it; standing on the floor of a block is being in it.
     */
    private static final double FLOOR_TOLERANCE = 1.0E-4d;

    private Goals() {
    }

    static Goal[] copy(Goal[] goals) {
        Validate.notNull(goals, "goals");
        Validate.check(goals.length > 0, "at least one goal is needed");
        for (Goal goal : goals) {
            Validate.notNull(goal, "goal");
        }
        return Arrays.copyOf(goals, goals.length);
    }

    /** @return the block a height is in, counting a hair under its floor as on it */
    static int blockY(double y) {
        return (int) Math.floor(y + FLOOR_TOLERANCE);
    }

    /** @return the horizontal distance from a point to a block column */
    static double acrossTo(int cellX, int cellZ, Vec3 feet) {
        double dx = Math.max(0d, Math.max(cellX - feet.getX(), feet.getX() - (cellX + 1)));
        double dz = Math.max(0d, Math.max(cellZ - feet.getZ(), feet.getZ() - (cellZ + 1)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** @return blocks to climb to a block's floor, or negative blocks to drop below its top */
    static double upTo(int cellY, Vec3 feet) {
        if (feet.getY() < cellY - FLOOR_TOLERANCE) {
            return cellY - feet.getY();
        }
        if (feet.getY() >= cellY + 1 - FLOOR_TOLERANCE) {
            return -(feet.getY() - (cellY + 1));
        }
        return 0d;
    }

    static final class InBlock implements Goal {

        private final Vec3i cell;

        InBlock(Vec3i cell) {
            this.cell = cell;
        }

        @Override
        public boolean isMet(Vec3 feet) {
            return (int) Math.floor(feet.getX()) == cell.getX()
                    && (int) Math.floor(feet.getZ()) == cell.getZ()
                    && blockY(feet.getY()) == cell.getY();
        }

        @Override
        public Gap gap(Vec3 feet) {
            return isMet(feet) ? Gap.NONE : Gap.of(acrossTo(cell.getX(), cell.getZ(), feet), upTo(cell.getY(), feet));
        }

        @Override
        public Vec3 anchor() {
            return Vec3.of(cell.getX() + 0.5d, cell.getY(), cell.getZ() + 0.5d);
        }

        @Override
        public String toString() {
            return "Goal.block(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")";
        }
    }

    static final class Near implements Goal {

        private final Supplier<Vec3> point;
        private final double radius;

        Near(Supplier<Vec3> point, double radius) {
            this.point = point;
            this.radius = radius;
        }

        @Override
        public boolean isMet(Vec3 feet) {
            Vec3 at = point.get();
            return at != null && feet.distanceTo(at) <= radius;
        }

        @Override
        public Gap gap(Vec3 feet) {
            Vec3 at = point.get();
            if (at == null) {
                return Gap.NONE;
            }
            double across = feet.horizontalDistanceTo(at) - radius;
            double dy = at.getY() - feet.getY();
            double up = Math.signum(dy) * Math.max(0d, Math.abs(dy) - radius);
            return Gap.of(across, up);
        }

        @Override
        public Vec3 anchor() {
            return point.get();
        }

        @Override
        public String toString() {
            return "Goal.near(" + point.get() + ", " + radius + ")";
        }
    }

    static final class Column implements Goal {

        private final int x;
        private final int z;

        Column(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean isMet(Vec3 feet) {
            return (int) Math.floor(feet.getX()) == x && (int) Math.floor(feet.getZ()) == z;
        }

        @Override
        public Gap gap(Vec3 feet) {
            return Gap.of(acrossTo(x, z, feet), 0d);
        }

        @Override
        public String toString() {
            return "Goal.column(" + x + ", " + z + ")";
        }
    }

    static final class Level implements Goal {

        private final int y;

        Level(int y) {
            this.y = y;
        }

        @Override
        public boolean isMet(Vec3 feet) {
            return blockY(feet.getY()) == y;
        }

        @Override
        public Gap gap(Vec3 feet) {
            return Gap.of(0d, upTo(y, feet));
        }

        @Override
        public String toString() {
            return "Goal.level(" + y + ")";
        }
    }

    static final class Avoid implements Goal {

        private final Box region;

        Avoid(Box region) {
            this.region = region;
        }

        @Override
        public boolean isMet(Vec3 feet) {
            return !region.contains(feet);
        }

        @Override
        public Gap gap(Vec3 feet) {
            return Gap.NONE;
        }

        @Override
        public String toString() {
            return "Goal.avoid(" + region + ")";
        }
    }

    static final class AnyOf implements Goal {

        private final Goal[] goals;

        AnyOf(Goal[] goals) {
            this.goals = goals;
        }

        @Override
        public boolean isMet(Vec3 feet) {
            for (Goal goal : goals) {
                if (goal.isMet(feet)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Gap gap(Vec3 feet) {
            Gap nearest = goals[0].gap(feet);
            for (int i = 1; i < goals.length; i++) {
                nearest = nearest.min(goals[i].gap(feet));
            }
            return nearest;
        }

        @Override
        public Vec3 anchor() {
            return firstAnchor(goals);
        }

        @Override
        public String toString() {
            return "Goal.anyOf" + Arrays.toString(goals);
        }
    }

    static final class AllOf implements Goal {

        private final Goal[] goals;

        AllOf(Goal[] goals) {
            this.goals = goals;
        }

        @Override
        public boolean isMet(Vec3 feet) {
            for (Goal goal : goals) {
                if (!goal.isMet(feet)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Gap gap(Vec3 feet) {
            Gap furthest = goals[0].gap(feet);
            for (int i = 1; i < goals.length; i++) {
                furthest = furthest.max(goals[i].gap(feet));
            }
            return furthest;
        }

        @Override
        public Vec3 anchor() {
            return firstAnchor(goals);
        }

        @Override
        public String toString() {
            return "Goal.allOf" + Arrays.toString(goals);
        }
    }

    private static Vec3 firstAnchor(Goal[] goals) {
        for (Goal goal : goals) {
            Vec3 anchor = goal.anchor();
            if (anchor != null) {
                return anchor;
            }
        }
        return null;
    }
}
