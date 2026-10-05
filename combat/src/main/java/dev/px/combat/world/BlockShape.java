package dev.px.combat.world;

import dev.px.core.math.Box;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a block cell holds, as an explosion's rays see it: nothing, the whole
 * cell, or a few boxes inside it.
 *
 * <p>Boxes are relative to the cell, so {@code (0,0,0)-(1,0.5,1)} is a bottom
 * slab wherever it is. Give the shape your game's ray test actually uses; Core
 * attaches no meaning to any block. Build one per kind of block and reuse it:
 * a shape is immutable, and asking for it once a ray step must not allocate.
 *
 * <pre>{@code
 * static final BlockShape SLAB = BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1));
 * }</pre>
 */
public final class BlockShape {

    /** Nothing there: rays pass. */
    public static final BlockShape EMPTY = new BlockShape(new double[0]);

    /** The whole cell: any ray entering it stops. */
    public static final BlockShape FULL = new BlockShape(new double[] { 0, 0, 0, 1, 1, 1 });

    /** Six values per box: min x, y, z, then max x, y, z, relative to the cell. */
    private final double[] boxes;

    private BlockShape(double[] boxes) {
        this.boxes = boxes;
    }

    /** @param boxes boxes relative to the cell; none is {@link #EMPTY} */
    public static BlockShape of(Box... boxes) {
        Validate.notNull(boxes, "boxes");
        if (boxes.length == 0) {
            return EMPTY;
        }
        double[] flat = new double[boxes.length * 6];
        for (int i = 0; i < boxes.length; i++) {
            Box box = Validate.notNull(boxes[i], "box");
            flat[i * 6] = box.getMin().getX();
            flat[i * 6 + 1] = box.getMin().getY();
            flat[i * 6 + 2] = box.getMin().getZ();
            flat[i * 6 + 3] = box.getMax().getX();
            flat[i * 6 + 4] = box.getMax().getY();
            flat[i * 6 + 5] = box.getMax().getZ();
        }
        return new BlockShape(flat);
    }

    public boolean isEmpty() {
        return boxes.length == 0;
    }

    /** @return the boxes, relative to the cell; empty for {@link #EMPTY} */
    public List<Box> getBoxes() {
        List<Box> out = new ArrayList<>(boxes.length / 6);
        for (int i = 0; i < boxes.length; i += 6) {
            out.add(Box.of(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5]));
        }
        return out;
    }

    /**
     * @return whether the segment from {@code (x1,y1,z1)} to {@code (x2,y2,z2)}
     *         passes through this shape in the cell at
     *         {@code (cellX,cellY,cellZ)}. A segment that starts inside it does;
     *         one that only touches it at a point does not; one lying in a face is
     *         inside in the low face and outside in the high one
     */
    public boolean intersects(int cellX, int cellY, int cellZ,
                              double x1, double y1, double z1, double x2, double y2, double z2) {
        for (int i = 0; i < boxes.length; i += 6) {
            if (segmentHitsBox(x1, y1, z1, x2, y2, z2,
                    cellX + boxes[i], cellY + boxes[i + 1], cellZ + boxes[i + 2],
                    cellX + boxes[i + 3], cellY + boxes[i + 4], cellZ + boxes[i + 5])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The slab test: clip the segment's parameter range against each axis in turn.
     *
     * <p>The segment must pass through the box for some length: one that only
     * touches it at a point, an edge or a corner is not stopped. A segment lying in
     * one of its faces follows the grid's own rule, that a boundary belongs to the
     * cell above it: in the low face it is inside, in the high face outside. So a
     * ray along the top of a floor is clear &mdash; a target is not hidden by the
     * block it stands on &mdash; while one along the seam between two stacked
     * blocks is stopped by the upper one, as a solid wall should stop it.
     */
    static boolean segmentHitsBox(double x1, double y1, double z1, double x2, double y2, double z2,
                                  double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double enter = 0d;
        double exit = 1d;
        double[] starts = { x1, y1, z1 };
        double[] deltas = { x2 - x1, y2 - y1, z2 - z1 };
        double[] mins = { minX, minY, minZ };
        double[] maxs = { maxX, maxY, maxZ };
        for (int axis = 0; axis < 3; axis++) {
            double start = starts[axis];
            double delta = deltas[axis];
            if (Math.abs(delta) < 1e-12) {
                if (start < mins[axis] || start >= maxs[axis]) {
                    return false;
                }
                continue;
            }
            double t1 = (mins[axis] - start) / delta;
            double t2 = (maxs[axis] - start) / delta;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            enter = Math.max(enter, t1);
            exit = Math.min(exit, t2);
            if (enter >= exit) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockShape && Arrays.equals(boxes, ((BlockShape) other).boxes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(boxes);
    }

    @Override
    public String toString() {
        return isEmpty() ? "BlockShape(empty)" : this.equals(FULL) ? "BlockShape(full)"
                : "BlockShape(" + boxes.length / 6 + " boxes)";
    }
}
