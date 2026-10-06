package dev.px.combat.hole;

import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds holes: {@link HoleShape#SINGLE singles}, {@link HoleShape#DOUBLE doubles}
 * along x or z, and {@link HoleShape#QUAD quads}, by your {@link HoleRules}.
 *
 * <pre>{@code
 * HoleFinder finder = new HoleFinder(holes);
 * for (Hole hole : finder.around(target.getPosition(), 5)) { ... }   // nearest first
 * }</pre>
 *
 * <p>Each hole is found once, by its lowest corner. A cell in a double is never
 * also reported as a single, and a quad's pairs are never doubles: their open
 * neighbour is not a wall.
 *
 * <p>Your tests are asked at most once per cell per search. A search of radius r
 * asks about (2⌈r⌉ + 1)³ cells and the few around each candidate. Game thread
 * only.
 */
public final class HoleFinder {

    private final HoleRules rules;

    public HoleFinder(HoleRules rules) {
        this.rules = Validate.notNull(rules, "rules");
    }

    public HoleRules getRules() {
        return rules;
    }

    /** @return every hole with a cell whose centre is within {@code radius} of {@code point}, nearest first */
    public List<Hole> around(Vec3 point, double radius) {
        Validate.notNull(point, "point");
        Validate.check(radius >= 0d, "radius must not be negative");
        Cells cells = new Cells();
        int reach = (int) Math.ceil(radius);
        int px = (int) Math.floor(point.getX());
        int py = (int) Math.floor(point.getY());
        int pz = (int) Math.floor(point.getZ());
        List<Hole> found = new ArrayList<>();
        for (int x = px - reach; x <= px + reach; x++) {
            for (int y = py - reach; y <= py + reach; y++) {
                for (int z = pz - reach; z <= pz + reach; z++) {
                    double dx = x + 0.5d - point.getX();
                    double dy = y + 0.5d - point.getY();
                    double dz = z + 0.5d - point.getZ();
                    if (dx * dx + dy * dy + dz * dz > radius * radius) {
                        continue;
                    }
                    Hole hole = anchoredAt(cells, x, y, z);
                    if (hole != null) {
                        found.add(hole);
                    }
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.getCentre().distanceTo(point), b.getCentre().distanceTo(point)));
        return found;
    }

    /** @return the hole that has {@code (x, y, z)} as one of its cells, or null */
    public Hole at(int x, int y, int z) {
        Cells cells = new Cells();
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                Hole hole = anchoredAt(cells, x + ox, y, z + oz);
                if (hole != null && hole.getCells().contains(Vec3i.of(x, y, z))) {
                    return hole;
                }
            }
        }
        return null;
    }

    /** @return the hole whose lowest corner is this cell, or null */
    private Hole anchoredAt(Cells cells, int x, int y, int z) {
        if (!cells.standable(x, y, z)) {
            return null;
        }
        if (rules.getShapes().contains(HoleShape.SINGLE)) {
            Hole single = walled(cells, HoleShape.SINGLE, x, y, z, 1, 1);
            if (single != null) {
                return single;
            }
        }
        if (rules.getShapes().contains(HoleShape.DOUBLE)) {
            Hole along = walled(cells, HoleShape.DOUBLE, x, y, z, 2, 1);
            if (along != null) {
                return along;
            }
            Hole across = walled(cells, HoleShape.DOUBLE, x, y, z, 1, 2);
            if (across != null) {
                return across;
            }
        }
        if (rules.getShapes().contains(HoleShape.QUAD)) {
            return walled(cells, HoleShape.QUAD, x, y, z, 2, 2);
        }
        return null;
    }

    /** @return a hole of these cells, from {@code (x, z)} {@code sizeX} by {@code sizeZ}, if each is standable and walled all round */
    private Hole walled(Cells cells, HoleShape shape, int x, int y, int z, int sizeX, int sizeZ) {
        List<Vec3i> inside = new ArrayList<>(sizeX * sizeZ);
        for (int ix = 0; ix < sizeX; ix++) {
            for (int iz = 0; iz < sizeZ; iz++) {
                if (!cells.standable(x + ix, y, z + iz)) {
                    return null;
                }
                inside.add(Vec3i.of(x + ix, y, z + iz));
            }
        }
        boolean safe = rules.judgesSafety();
        for (int ix = -1; ix <= sizeX; ix++) {
            for (int iz = -1; iz <= sizeZ; iz++) {
                boolean edgeX = ix == -1 || ix == sizeX;
                boolean edgeZ = iz == -1 || iz == sizeZ;
                if (edgeX == edgeZ) {
                    continue;                        // inside, or a corner: corners do not matter
                }
                if (!cells.wall(x + ix, y, z + iz)) {
                    return null;
                }
                safe &= cells.safe(x + ix, y, z + iz);
            }
        }
        for (Vec3i cell : inside) {
            safe &= cells.safe(cell.getX(), y - 1, cell.getZ());
        }
        return new Hole(shape, inside, safe);
    }

    /** Your tests' answers for one search, each asked at most once. */
    private final class Cells {
        private final Map<Long, byte[]> answers = new HashMap<>();

        boolean standable(int x, int y, int z) {
            if (!floor(x, y - 1, z)) {
                return false;
            }
            for (int up = 0; up < rules.getHeadroom(); up++) {
                if (!open(x, y + up, z)) {
                    return false;
                }
            }
            return true;
        }

        boolean wall(int x, int y, int z) {
            return ask(x, y, z, 0);
        }

        boolean floor(int x, int y, int z) {
            return ask(x, y, z, 1);
        }

        boolean open(int x, int y, int z) {
            return ask(x, y, z, 2);
        }

        boolean safe(int x, int y, int z) {
            return ask(x, y, z, 3);
        }

        /** @param which 0 wall, 1 floor, 2 open, 3 safe */
        private boolean ask(int x, int y, int z, int which) {
            long key = ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
            byte[] known = answers.get(key);
            if (known == null) {
                known = new byte[4];
                Arrays.fill(known, (byte) -1);
                answers.put(key, known);
            }
            if (known[which] < 0) {
                boolean answer;
                switch (which) {
                    case 0:
                        answer = rules.isWall(x, y, z);
                        break;
                    case 1:
                        answer = rules.isFloor(x, y, z);
                        break;
                    case 2:
                        answer = rules.isOpen(x, y, z);
                        break;
                    default:
                        answer = rules.isSafe(x, y, z);
                        break;
                }
                known[which] = (byte) (answer ? 1 : 0);
            }
            return known[which] == 1;
        }
    }
}
