package dev.px.navigation.plan;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.simulation.CollisionSpace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world as one plan sees it, asked about each block once.
 *
 * <p>A plan simulates thousands of ticks over the same few hundred blocks, and
 * {@link dev.px.core.movement.simulation.Simulation} asks the world about every
 * one of them. This answers each block from what it was told the first time,
 * which is also what makes a plan consistent: the world cannot change halfway
 * through one.
 *
 * <p>Asks the adapter's {@link CollisionSpace} about the inside of one block at a
 * time, so a box that reaches beyond its block &mdash; a fence, a wall &mdash; is
 * found from whichever block the adapter reports it for.
 *
 * <p>Lives for one plan. Not thread safe.
 */
final class CellCache implements CollisionSpace {

    /** How far inside a block its query stays, so a neighbour's touching face is not reported twice. */
    private static final double INSET = 1.0E-6d;

    private final CollisionSpace world;
    private final Map<Long, List<Box>> boxes = new HashMap<>();
    private final Map<Long, Double> slipperiness = new HashMap<>();
    private int queries;

    CellCache(CollisionSpace world) {
        this.world = world;
    }

    /** @return how many times the adapter's world was asked */
    int getQueries() {
        return queries;
    }

    @Override
    public List<Box> boxesIn(Box region) {
        int minX = (int) Math.floor(region.getMinX());
        int maxX = (int) Math.floor(region.getMaxX());
        int minY = (int) Math.floor(region.getMinY());
        int maxY = (int) Math.floor(region.getMaxY());
        int minZ = (int) Math.floor(region.getMinZ());
        int maxZ = (int) Math.floor(region.getMaxZ());
        List<Box> found = null;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    List<Box> inCell = cell(x, y, z);
                    if (inCell.isEmpty()) {
                        continue;
                    }
                    if (found == null) {
                        found = new ArrayList<>();
                    }
                    found.addAll(inCell);
                }
            }
        }
        return found == null ? Collections.<Box>emptyList() : found;
    }

    @Override
    public double slipperinessAt(Vec3 position) {
        long key = Vec3i.asLong((int) Math.floor(position.getX()), (int) Math.floor(position.getY()),
                (int) Math.floor(position.getZ()));
        Double known = slipperiness.get(key);
        if (known == null) {
            known = world.slipperinessAt(position);
            slipperiness.put(key, known);
        }
        return known;
    }

    private List<Box> cell(int x, int y, int z) {
        long key = Vec3i.asLong(x, y, z);
        List<Box> known = boxes.get(key);
        if (known == null) {
            queries++;
            List<Box> asked = world.boxesIn(Box.of(x + INSET, y + INSET, z + INSET,
                    x + 1 - INSET, y + 1 - INSET, z + 1 - INSET));
            known = asked == null || asked.isEmpty()
                    ? Collections.<Box>emptyList()
                    : new ArrayList<>(asked);
            boxes.put(key, known);
        }
        return known;
    }
}
