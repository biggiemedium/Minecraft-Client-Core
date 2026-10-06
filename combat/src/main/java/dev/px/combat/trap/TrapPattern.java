package dev.px.combat.trap;

import dev.px.combat.place.Shapes;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which cells around a player make a trap: the cells, each tagged with the part
 * of the trap it is, for wherever the player stands.
 *
 * <pre>{@code
 * TrapPattern.FULL         // a ring at their feet, a ring at their head, a roof
 * TrapPattern.TOP_ONLY     // the head ring and the roof: for someone already in a hole
 * TrapPattern.ANTI_STEP    // the full trap, and a ring over the head ring, so nothing can be stepped or jumped onto
 * TrapPattern.builder().head().roof()
 *         .offset(TrapPattern.Part.EXTRA, 2, 0, 0)      // and anything else, relative to their feet
 *         .build()
 * }</pre>
 *
 * <p>The rings follow the player's box: on a block edge their feet are in two
 * cells and the ring around them is six, on a corner eight. Custom offsets are
 * taken from each cell their feet are in, and never land inside them.
 *
 * <p>Immutable.
 */
public final class TrapPattern {

    /** Which part of a trap a cell is: what it stops. */
    public enum Part {

        /** Beside their feet: stops them walking out. */
        FEET,

        /** Beside their head: stops them climbing out, and covers their head from the side. */
        HEAD,

        /** Over their head: stops them jumping, so out of a hole, or onto anything. */
        ROOF,

        /** Anything else: an anti-step layer, or offsets of your own. */
        EXTRA
    }

    /** One cell of a trap, and which part it is. */
    public static final class Cell {
        private final Vec3i cell;
        private final Part part;

        Cell(Vec3i cell, Part part) {
            this.cell = cell;
            this.part = part;
        }

        public Vec3i getCell() {
            return cell;
        }

        public Part getPart() {
            return part;
        }

        @Override
        public String toString() {
            return part + " " + cell;
        }
    }

    /** A ring at their feet, a ring at their head, and a roof. */
    public static final TrapPattern FULL = builder().feet().head().roof().build();

    /** The head ring and the roof: their feet are already walled, as in a hole. */
    public static final TrapPattern TOP_ONLY = builder().head().roof().build();

    /** The full trap, and a ring over the head ring, so there is nothing to step or jump onto. */
    public static final TrapPattern ANTI_STEP = builder().feet().head().roof().antiStep().build();

    private final boolean feet;
    private final boolean head;
    private final boolean roof;
    private final boolean antiStep;
    private final List<int[]> offsets;
    private final List<Part> offsetParts;

    private TrapPattern(Builder builder) {
        this.feet = builder.feet;
        this.head = builder.head;
        this.roof = builder.roof;
        this.antiStep = builder.antiStep;
        this.offsets = Collections.unmodifiableList(new ArrayList<>(builder.offsets));
        this.offsetParts = Collections.unmodifiableList(new ArrayList<>(builder.offsetParts));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return the trap's cells around a player whose box is {@code box}, bottom up, each once */
    public List<Cell> cells(Box box) {
        Validate.notNull(box, "box");
        Set<Vec3i> inside = new LinkedHashSet<>(Shapes.occupied(box));
        Map<Vec3i, Part> cells = new LinkedHashMap<>();
        List<Vec3i> feetCells = Shapes.feet(box);
        List<Vec3i> headCells = Shapes.head(box);
        if (feet) {
            put(cells, inside, Shapes.beside(feetCells), Part.FEET);
        }
        if (head) {
            put(cells, inside, Shapes.beside(headCells), Part.HEAD);
        }
        if (roof) {
            put(cells, inside, Shapes.above(box), Part.ROOF);
        }
        if (antiStep) {
            List<Vec3i> overHead = new ArrayList<>();
            for (Vec3i cell : headCells) {
                overHead.add(cell.up());
            }
            put(cells, inside, Shapes.beside(overHead), Part.EXTRA);
        }
        for (int i = 0; i < offsets.size(); i++) {
            int[] offset = offsets.get(i);
            List<Vec3i> placed = new ArrayList<>();
            for (Vec3i cell : feetCells) {
                placed.add(cell.add(offset[0], offset[1], offset[2]));
            }
            put(cells, inside, placed, offsetParts.get(i));
        }
        List<Cell> list = new ArrayList<>(cells.size());
        for (Map.Entry<Vec3i, Part> entry : cells.entrySet()) {
            list.add(new Cell(entry.getKey(), entry.getValue()));
        }
        return list;
    }

    private static void put(Map<Vec3i, Part> cells, Set<Vec3i> inside, List<Vec3i> more, Part part) {
        for (Vec3i cell : more) {
            if (!inside.contains(cell) && !cells.containsKey(cell)) {
                cells.put(cell, part);
            }
        }
    }

    public static final class Builder {

        private boolean feet;
        private boolean head;
        private boolean roof;
        private boolean antiStep;
        private final List<int[]> offsets = new ArrayList<>();
        private final List<Part> offsetParts = new ArrayList<>();

        private Builder() {
        }

        /** The ring beside their feet. */
        public Builder feet() {
            this.feet = true;
            return this;
        }

        /** The ring beside their head. */
        public Builder head() {
            this.head = true;
            return this;
        }

        /** The cells over their head. */
        public Builder roof() {
            this.roof = true;
            return this;
        }

        /** A ring over the head ring: nothing beside them to step or jump onto. */
        public Builder antiStep() {
            this.antiStep = true;
            return this;
        }

        /** A cell of your own, {@code dx, dy, dz} from each cell their feet are in, counted as {@code part}. */
        public Builder offset(Part part, int dx, int dy, int dz) {
            Validate.notNull(part, "part");
            this.offsets.add(new int[] { dx, dy, dz });
            this.offsetParts.add(part);
            return this;
        }

        /** @throws IllegalStateException for a pattern with no cells */
        public TrapPattern build() {
            if (!feet && !head && !roof && !antiStep && offsets.isEmpty()) {
                throw new IllegalStateException("a trap pattern needs at least one part or offset");
            }
            return new TrapPattern(this);
        }
    }
}
