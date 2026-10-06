package dev.px.combat.place;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The cells around a box that blocks get placed into: what a web, a surround, a
 * trap or a self-trap fills, for whoever's box it is.
 *
 * <pre>{@code
 * Shapes.occupied(box)     // every cell it is in: what webs go into
 * Shapes.feet(box)         // the cells at its feet
 * Shapes.head(box)         // the cells at its head
 * Shapes.around(box)       // the cells beside its feet: a surround
 * Shapes.above(box)        // the cells over its head: a trap's roof
 * }</pre>
 *
 * <p>A player standing on the edge between blocks is in two of them, or four on a
 * corner, and every shape follows: feet on a corner are four cells, and the ring
 * around them is eight. Lists are in a fixed order, feet before head, so they can
 * go straight to a {@link PlacementPlanner}, which places in the order given. Pass
 * a box where the entity will be &mdash; {@code Lookahead.at(entity, ticks).getBox()}
 * &mdash; to place where it is going.
 *
 * <p>Geometry only: whether a cell is free, or worth filling, is your rules' and
 * your module's to say.
 */
public final class Shapes {

    /** How far inside a cell a box must reach to count as in it: a box only touching a cell's side is not. */
    private static final double SLACK = 1e-6d;

    private Shapes() {
    }

    /** @return every cell {@code box} is in, bottom layer first */
    public static List<Vec3i> occupied(Box box) {
        Validate.notNull(box, "box");
        List<Vec3i> cells = new ArrayList<>();
        for (int y = low(box.getMinY()); y <= high(box.getMaxY()); y++) {
            cells.addAll(layer(box, y));
        }
        return cells;
    }

    /** @return the cells {@code box}'s feet are in */
    public static List<Vec3i> feet(Box box) {
        Validate.notNull(box, "box");
        return layer(box, low(box.getMinY()));
    }

    /** @return the cells the top of {@code box} is in */
    public static List<Vec3i> head(Box box) {
        Validate.notNull(box, "box");
        return layer(box, high(box.getMaxY()));
    }

    /** @return the cells beside {@code box}'s feet, at the same height, that it is not in: a surround */
    public static List<Vec3i> around(Box box) {
        return beside(feet(box));
    }

    /** @return the cells directly over {@code box}'s head */
    public static List<Vec3i> above(Box box) {
        List<Vec3i> over = new ArrayList<>();
        for (Vec3i cell : head(box)) {
            over.add(cell.up());
        }
        return over;
    }

    /** @return the cells beside {@code cells}, sideways, that are not themselves among them: a ring */
    public static List<Vec3i> beside(Collection<Vec3i> cells) {
        Validate.notNull(cells, "cells");
        Set<Vec3i> inside = new LinkedHashSet<>(cells);
        Set<Vec3i> ring = new LinkedHashSet<>();
        for (Vec3i cell : cells) {
            for (Vec3i next : new Vec3i[] {
                    cell.add(0, 0, -1), cell.add(1, 0, 0), cell.add(0, 0, 1), cell.add(-1, 0, 0) }) {
                if (!inside.contains(next)) {
                    ring.add(next);
                }
            }
        }
        return new ArrayList<>(ring);
    }

    /** @return every cell in any of {@code shapes}, each once, in the order first met */
    @SafeVarargs
    public static List<Vec3i> union(Collection<Vec3i>... shapes) {
        Set<Vec3i> all = new LinkedHashSet<>();
        for (Collection<Vec3i> shape : shapes) {
            all.addAll(Validate.notNull(shape, "shape"));
        }
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    /** @return the cells at height {@code y} that {@code box} reaches into, north-west first */
    private static List<Vec3i> layer(Box box, int y) {
        List<Vec3i> cells = new ArrayList<>(4);
        for (int z = low(box.getMinZ()); z <= high(box.getMaxZ()); z++) {
            for (int x = low(box.getMinX()); x <= high(box.getMaxX()); x++) {
                cells.add(Vec3i.of(x, y, z));
            }
        }
        return cells;
    }

    /** @return the cell a box's low side is in: one it only touches from inside does not count */
    private static int low(double min) {
        return (int) Math.floor(min + SLACK);
    }

    /** @return the cell a box's high side is in: one it only touches from below does not count */
    private static int high(double max) {
        return (int) Math.floor(max - SLACK);
    }
}
