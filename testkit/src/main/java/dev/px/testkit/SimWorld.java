package dev.px.testkit;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A world made of blocks put there by the test.
 *
 * <pre>{@code
 * world.floor(64, -20, 20)                       // a floor whose top is at y = 64
 *      .fill(5, 64, -3, 5, 65, 3)                // a wall two blocks high
 *      .slab(8, 64, 0, 0.5)                      // half a block
 *      .remove(0, 63, 4);                        // a hole in the floor
 * world.slipperiness(2, 63, 2, 0.98);            // ice, if your rules say so
 * }</pre>
 *
 * <p>It is both a {@link CollisionSpace}, which the simulation and the planner
 * move through, and a {@link BlockView}, which rays and the combat library read,
 * so a test needs only one world. Nothing here knows what a block is: a cell is
 * empty, full, or the shape you give it.
 *
 * <p>Changing it between ticks is how a test changes the world under a running
 * flow.
 */
public final class SimWorld implements CollisionSpace, BlockView {

    private final Map<Long, BlockShape> blocks = new HashMap<>();
    private final Map<Long, Double> slipperiness = new HashMap<>();
    private final double defaultSlipperiness;
    private int queries;

    SimWorld(PhysicsProfile profile) {
        this.defaultSlipperiness = profile.getDefaultSlipperiness();
    }

    /** Fills a cell. */
    public SimWorld solid(int x, int y, int z) {
        return shape(x, y, z, BlockShape.FULL);
    }

    /** A box of {@code height} sitting on the bottom of the cell. */
    public SimWorld slab(int x, int y, int z, double height) {
        Validate.check(height > 0d, "height must be above zero, got " + height);
        return shape(x, y, z, BlockShape.of(Box.of(0, 0, 0, 1, height, 1)));
    }

    /** Any shape, relative to the cell. */
    public SimWorld shape(int x, int y, int z, BlockShape shape) {
        Validate.notNull(shape, "shape");
        if (shape.isEmpty()) {
            blocks.remove(Vec3i.asLong(x, y, z));
        } else {
            blocks.put(Vec3i.asLong(x, y, z), shape);
        }
        return this;
    }

    public SimWorld remove(int x, int y, int z) {
        blocks.remove(Vec3i.asLong(x, y, z));
        return this;
    }

    /** Fills every cell between two corners, both included. */
    public SimWorld fill(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    solid(x, y, z);
                }
            }
        }
        return this;
    }

    /** Empties every cell between two corners, both included. */
    public SimWorld clear(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    remove(x, y, z);
                }
            }
        }
        return this;
    }

    /** A flat floor, one block thick, whose top is at {@code topY}, from {@code from} to {@code to} each way. */
    public SimWorld floor(int topY, int from, int to) {
        return fill(from, topY - 1, from, to, topY - 1, to);
    }

    /** How slippery the top of one cell is, as your rules give it; the rest keep the profile's default. */
    public SimWorld slipperiness(int x, int y, int z, double value) {
        slipperiness.put(Vec3i.asLong(x, y, z), value);
        return this;
    }

    public boolean isSolid(int x, int y, int z) {
        return blocks.containsKey(Vec3i.asLong(x, y, z));
    }

    /** @return how many times {@link #boxesIn} has been asked */
    public int getQueries() {
        return queries;
    }

    @Override
    public BlockShape shapeAt(int x, int y, int z) {
        BlockShape shape = blocks.get(Vec3i.asLong(x, y, z));
        return shape == null ? BlockShape.EMPTY : shape;
    }

    @Override
    public List<Box> boxesIn(Box region) {
        queries++;
        List<Box> found = new ArrayList<>();
        int minX = (int) Math.floor(region.getMinX());
        int maxX = (int) Math.floor(region.getMaxX());
        // One below, for shapes taller than their cell.
        int minY = (int) Math.floor(region.getMinY()) - 1;
        int maxY = (int) Math.floor(region.getMaxY());
        int minZ = (int) Math.floor(region.getMinZ());
        int maxZ = (int) Math.floor(region.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockShape shape = blocks.get(Vec3i.asLong(x, y, z));
                    if (shape == null) {
                        continue;
                    }
                    for (Box box : shape.getBoxes()) {
                        Box placed = box.offset(x, y, z);
                        if (placed.intersects(region)) {
                            found.add(placed);
                        }
                    }
                }
            }
        }
        return found;
    }

    @Override
    public double slipperinessAt(Vec3 position) {
        Double value = slipperiness.get(Vec3i.asLong((int) Math.floor(position.getX()),
                (int) Math.floor(position.getY()), (int) Math.floor(position.getZ())));
        return value != null ? value : defaultSlipperiness;
    }
}
