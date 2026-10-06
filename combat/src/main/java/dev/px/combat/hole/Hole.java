package dev.px.combat.hole;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.simulation.MotionState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One hole: its cells, at the height a player's feet are in it, and whether it
 * is safe.
 *
 * <p>Equal by shape and cells, so the same hole found again is the same hole.
 * Immutable.
 */
public final class Hole {

    /** How far a box may stick out of the footprint, or feet sit above the floor, and still count. */
    private static final double SLACK = 1e-6d;

    private final HoleShape shape;
    private final List<Vec3i> cells;
    private final boolean safe;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int y;

    Hole(HoleShape shape, List<Vec3i> cells, boolean safe) {
        this.shape = shape;
        this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        this.safe = safe;
        int lowX = Integer.MAX_VALUE;
        int lowZ = Integer.MAX_VALUE;
        int highX = Integer.MIN_VALUE;
        int highZ = Integer.MIN_VALUE;
        for (Vec3i cell : cells) {
            lowX = Math.min(lowX, cell.getX());
            lowZ = Math.min(lowZ, cell.getZ());
            highX = Math.max(highX, cell.getX());
            highZ = Math.max(highZ, cell.getZ());
        }
        this.minX = lowX;
        this.minZ = lowZ;
        this.maxX = highX + 1;
        this.maxZ = highZ + 1;
        this.y = cells.get(0).getY();
    }

    public HoleShape getShape() {
        return shape;
    }

    /** @return the cells a player's feet are in, inside it */
    public List<Vec3i> getCells() {
        return cells;
    }

    /** @return whether every wall and floor block passed your safe test; false without one */
    public boolean isSafe() {
        return safe;
    }

    /** @return the height of its floor's top: where a player's feet are, inside it */
    public int getY() {
        return y;
    }

    /** @return the cells at foot height, as one box: where a player inside it is */
    public Box getBox() {
        return Box.of(minX, y, minZ, maxX, y + 1, maxZ);
    }

    /** @return the middle of its floor: where to head for to drop in */
    public Vec3 getCentre() {
        return Vec3.of((minX + maxX) / 2d, y, (minZ + maxZ) / 2d);
    }

    /**
     * @return whether feet at {@code state}, with a box {@code width} wide, are in
     *         it: the whole box inside its footprint, and the feet below the top of
     *         its walls
     */
    public boolean contains(MotionState state, double width) {
        return contains(state.getPosition(), width);
    }

    /** @return whether {@code entity} is in it, by the same test */
    public boolean contains(Tracked<?> entity) {
        return contains(entity.getPosition(), entity.getWidth());
    }

    private boolean contains(Vec3 feet, double width) {
        double half = width / 2d;
        return feet.getX() - half >= minX - SLACK && feet.getX() + half <= maxX + SLACK
                && feet.getZ() - half >= minZ - SLACK && feet.getZ() + half <= maxZ + SLACK
                && feet.getY() >= y - SLACK && feet.getY() < y + 1 - SLACK;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Hole && ((Hole) other).shape == shape && ((Hole) other).cells.equals(cells);
    }

    @Override
    public int hashCode() {
        return 31 * shape.hashCode() + cells.hashCode();
    }

    @Override
    public String toString() {
        return "Hole(" + shape + " at " + minX + ", " + y + ", " + minZ + (safe ? ", safe" : "") + ")";
    }
}
