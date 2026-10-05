package dev.px.core.test.harness;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.CollisionSpace;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A world made of blocks put there by hand.
 *
 * <p>The same trick {@code SpatialTests} uses for mazes: the algorithm under test
 * only ever asks "what is solid near here", so a world is a set of coordinates
 * and nothing about it needs a game. A floor, a wall and a half-height step are
 * enough to pin down everything the sweep promises.
 *
 * <p>Counts its own queries, because one of the promises is that a tick costs a
 * single call into the world however many axis sweeps it takes.
 */
public final class GridCollisionSpace implements CollisionSpace {

    /** Block position to the height of the box at it. 1.0 is a full cube. */
    private final Map<Long, Double> blocks = new HashMap<>();

    /** How many times {@link #boxesIn} has been asked. */
    @Getter
    private int queryCount;

    /** Reported for every position, so a test can make the whole world ice. */
    @Setter
    private double slipperiness = 0.6d;

    /** Adds a full cube. */
    public GridCollisionSpace solid(int x, int y, int z) {
        return solid(x, y, z, 1d);
    }

    /** Adds a box of {@code height} sitting on the bottom of that block. */
    public GridCollisionSpace solid(int x, int y, int z, double height) {
        blocks.put(key(x, y, z), height);
        return this;
    }

    /** A flat floor whose top surface is at {@code topY}. */
    public GridCollisionSpace floor(double topY, int from, int to) {
        int y = (int) Math.floor(topY) - 1;
        for (int x = from; x <= to; x++) {
            for (int z = from; z <= to; z++) {
                solid(x, y, z);
            }
        }
        return this;
    }

    /** A wall of full blocks at {@code x}, two high, spanning z. */
    public GridCollisionSpace wallAtX(int x, int baseY, int from, int to) {
        for (int z = from; z <= to; z++) {
            solid(x, baseY, z);
            solid(x, baseY + 1, z);
        }
        return this;
    }

    public void resetQueryCount() {
        queryCount = 0;
    }

    @Override
    public List<Box> boxesIn(Box region) {
        queryCount++;
        List<Box> found = new ArrayList<>();
        int minX = (int) Math.floor(region.getMinX());
        int maxX = (int) Math.floor(region.getMaxX());
        int minY = (int) Math.floor(region.getMinY());
        int maxY = (int) Math.floor(region.getMaxY());
        int minZ = (int) Math.floor(region.getMinZ());
        int maxZ = (int) Math.floor(region.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Double height = blocks.get(key(x, y, z));
                    if (height != null) {
                        found.add(Box.of(x, y, z, x + 1, y + height, z + 1));
                    }
                }
            }
        }
        return found;
    }

    @Override
    public double slipperinessAt(Vec3 position) {
        return slipperiness;
    }

    /** Test coordinates are small, so a naive pack is plenty. */
    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }
}
