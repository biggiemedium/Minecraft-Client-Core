package dev.px.combat.hole;

import dev.px.combat.world.CellTest;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a hole is, in your game: which blocks wall one in, which cells a player
 * can stand in, and how much room they need. The library knows the shapes; you
 * know the blocks.
 *
 * <pre>{@code
 * HoleRules holes = HoleRules.builder()
 *         .walls((x, y, z) -> Game.isObsidianOrBedrock(x, y, z) || Game.isEnderChest(x, y, z))
 *         .open((x, y, z) -> Game.isAirOrReplaceable(x, y, z))
 *         .headroom(2)                                           // a player needs two cells to stand in
 *         .safe((x, y, z) -> Game.isBedrock(x, y, z))           // bedrock all round: nothing breaks it
 *         .build();
 * }</pre>
 *
 * <p>A hole is open cells, each with a floor under it that {@link Builder#floor}
 * accepts, the room above each that {@link Builder#headroom} asks for, and a
 * {@link Builder#walls wall} beside every side that is not another of its own
 * cells. Only the cells at the player's feet are walled: the walls need not be
 * as tall as the player.
 *
 * <p>Immutable.
 */
public final class HoleRules {

    private final CellTest walls;
    private final CellTest floor;
    private final CellTest open;
    private final CellTest safe;
    private final int headroom;
    private final Set<HoleShape> shapes;

    private HoleRules(Builder builder) {
        this.walls = builder.walls;
        this.floor = builder.floor != null ? builder.floor : builder.walls;
        this.open = builder.open;
        this.safe = builder.safe;
        this.headroom = builder.headroom;
        this.shapes = Collections.unmodifiableSet(EnumSet.copyOf(builder.shapes));
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isWall(int x, int y, int z) {
        return walls.test(x, y, z);
    }

    public boolean isFloor(int x, int y, int z) {
        return floor.test(x, y, z);
    }

    public boolean isOpen(int x, int y, int z) {
        return open.test(x, y, z);
    }

    /** @return whether a wall or floor block makes a hole safe; false for every block without a safe test */
    public boolean isSafe(int x, int y, int z) {
        return safe != null && safe.test(x, y, z);
    }

    /** @return whether a safe test was given, so a hole can be safe at all */
    public boolean judgesSafety() {
        return safe != null;
    }

    /** @return how many open cells, from the feet up, a player needs to stand in a hole */
    public int getHeadroom() {
        return headroom;
    }

    /** @return the shapes looked for */
    public Set<HoleShape> getShapes() {
        return shapes;
    }

    public static final class Builder {

        private CellTest walls;
        private CellTest floor;
        private CellTest open;
        private CellTest safe;
        private int headroom = -1;
        private Set<HoleShape> shapes = EnumSet.allOf(HoleShape.class);

        private Builder() {
        }

        /** Required: the blocks a hole is walled with. They floor it too, unless {@link #floor} says otherwise. */
        public Builder walls(CellTest walls) {
            this.walls = Validate.notNull(walls, "walls");
            return this;
        }

        /** The blocks a hole may be floored with; its walls unless set. */
        public Builder floor(CellTest floor) {
            this.floor = Validate.notNull(floor, "floor");
            return this;
        }

        /** Required: the cells a player can stand in: air, and whatever else your game lets them stand in. */
        public Builder open(CellTest open) {
            this.open = Validate.notNull(open, "open");
            return this;
        }

        /** Required: how many open cells, from the feet up, a player needs to stand in a hole. */
        public Builder headroom(int cells) {
            Validate.check(cells >= 1, "headroom must be at least one cell");
            this.headroom = cells;
            return this;
        }

        /** Optional: the blocks that make a hole safe. A hole is safe when every wall and floor block is. */
        public Builder safe(CellTest safe) {
            this.safe = Validate.notNull(safe, "safe");
            return this;
        }

        /** The shapes to look for; all of them unless set. */
        public Builder shapes(HoleShape... shapes) {
            Validate.check(shapes.length > 0, "at least one shape is needed");
            this.shapes = EnumSet.copyOf(Arrays.asList(shapes));
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public HoleRules build() {
            StringBuilder missing = new StringBuilder();
            if (walls == null) {
                missing.append(" walls");
            }
            if (open == null) {
                missing.append(" open");
            }
            if (headroom < 1) {
                missing.append(" headroom");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("HoleRules need:" + missing);
            }
            return new HoleRules(this);
        }
    }
}
