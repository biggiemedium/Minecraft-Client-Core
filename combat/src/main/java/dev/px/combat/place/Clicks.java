package dev.px.combat.place;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;
import dev.px.core.util.math.RotationMath;
import dev.px.core.world.CellTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * How placing works on your server: what can be clicked, what a placed block
 * can go into, which faces it accepts, where on them to click. Everything here
 * is yours; what the library adds is the geometry of finding every way to place
 * something, and picking one.
 *
 * <pre>{@code
 * Clicks clicks = Clicks.builder()
 *         .support((x, y, z) -> Game.isSolid(x, y, z) && !Game.opensWhenClicked(x, y, z))
 *         .replaceable((x, y, z) -> Game.isAirOrReplaceable(x, y, z))
 *         .faces(FaceRule.facingEye().and(FaceRule.reach(Reach.of(4.5, 3), blocks)))
 *         .hit(HitPoint.CENTRE)
 *         .build();
 *
 * Click into = clicks.best(eye, hole, looking);       // to fill a hole: against a block beside it
 * Click on = clicks.bestOn(eye, base, looking);       // to put a crystal on a base: the base itself
 * }</pre>
 *
 * <p>Placing a block puts it beside the block clicked, on the face clicked: so
 * the ways to place into a cell are the faces of its neighbours that turn toward
 * it. Some items go on the block clicked whatever the face &mdash; a crystal goes
 * on top of its base &mdash; and {@link #on} lists those. A server that accepts a
 * click on nothing at all is an {@link Builder#airPlace air place}.
 *
 * <p>Immutable. Each call asks your tests about at most the cell and its six
 * neighbours.
 */
public final class Clicks {

    private final CellTest support;
    private final CellTest replaceable;
    private final FaceRule faces;
    private final HitPoint hit;
    private final BooleanSupplier airPlace;

    private Clicks(Builder builder) {
        this.support = builder.support;
        this.replaceable = builder.replaceable;
        this.faces = builder.faces;
        this.hit = builder.hit;
        this.airPlace = builder.airPlace;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return every way to place something into {@code cell} that your rules accept; empty when there is none */
    public List<Click> into(Vec3 eye, Vec3i cell) {
        return into(eye, cell, Collections.<Vec3i>emptySet());
    }

    /** @return the way into {@code cell} nearest your eyes, or null when there is none */
    public Click best(Vec3 eye, Vec3i cell) {
        return pick(eye, into(eye, cell), null);
    }

    /** @return the way into {@code cell} least turn from where you look, or null when there is none */
    public Click best(Vec3 eye, Vec3i cell, Vec2 looking) {
        return pick(eye, into(eye, cell), looking);
    }

    /**
     * @return every way to click {@code block} itself that your rules accept: for
     *         items that go on the block clicked, whatever the face. Each click's
     *         cell is the one beside the face
     */
    public List<Click> on(Vec3 eye, Vec3i block) {
        List<Click> found = new ArrayList<>(6);
        for (Direction face : Direction.values()) {
            Vec3 point = hit.on(eye, block, face);
            if (faces.allows(eye, block, face, point)) {
                found.add(new Click(block, face, point, block.add(face.toVec3i()), false));
            }
        }
        return found;
    }

    /** @return the way to click {@code block} nearest your eyes, or null */
    public Click bestOn(Vec3 eye, Vec3i block) {
        return pick(eye, on(eye, block), null);
    }

    /** @return the way to click {@code block} least turn from where you look, or null */
    public Click bestOn(Vec3 eye, Vec3i block, Vec2 looking) {
        return pick(eye, on(eye, block), looking);
    }

    /** @return whether something can be placed into {@code cell}, as your replaceable test says */
    public boolean isReplaceable(Vec3i cell) {
        return replaceable.test(cell.getX(), cell.getY(), cell.getZ());
    }

    /**
     * The ways into {@code cell}, with {@code placed} counted as already there:
     * solid to click against, and no longer room to place into.
     */
    List<Click> into(Vec3 eye, Vec3i cell, Set<Vec3i> placed) {
        if (placed.contains(cell) || !isReplaceable(cell)) {
            return Collections.emptyList();
        }
        List<Click> found = new ArrayList<>(6);
        for (Direction toward : Direction.values()) {
            // The neighbour on this side, clicked on its face that turns back toward the cell.
            Vec3i block = cell.add(toward.opposite().toVec3i());
            if (!placed.contains(block) && !support.test(block.getX(), block.getY(), block.getZ())) {
                continue;
            }
            Vec3 point = hit.on(eye, block, toward);
            if (faces.allows(eye, block, toward, point)) {
                found.add(new Click(block, toward, point, cell, false));
            }
        }
        if (found.isEmpty() && airPlace.getAsBoolean()) {
            Direction face = Direction.nearest(eye.subtract(cell.center()));
            Vec3 point = hit.on(eye, cell, face);
            if (faces.allows(eye, cell, face, point)) {
                found.add(new Click(cell, face, point, cell, true));
            }
        }
        return found;
    }

    static Click pick(Vec3 eye, List<Click> clicks, Vec2 looking) {
        Click best = null;
        double least = Double.POSITIVE_INFINITY;
        for (Click click : clicks) {
            double cost = looking != null
                    ? RotationMath.difference(looking, eye.rotationTo(click.getHit()))
                    : click.getHit().distanceTo(eye);
            if (cost < least) {
                least = cost;
                best = click;
            }
        }
        return best;
    }

    public static final class Builder {

        private CellTest support;
        private CellTest replaceable;
        private FaceRule faces = FaceRule.ANY;
        private HitPoint hit = HitPoint.CENTRE;
        private BooleanSupplier airPlace = () -> false;

        private Builder() {
        }

        /** Required: the blocks you can click to place against. Leave out what opens when clicked, unless you sneak. */
        public Builder support(CellTest support) {
            this.support = Validate.notNull(support, "support");
            return this;
        }

        /** Required: the cells a placed block can go into: air, and whatever else your game replaces. */
        public Builder replaceable(CellTest replaceable) {
            this.replaceable = Validate.notNull(replaceable, "replaceable");
            return this;
        }

        /** Which clicks your server accepts. {@link FaceRule#ANY}, vanilla, unless set. */
        public Builder faces(FaceRule faces) {
            this.faces = Validate.notNull(faces, "faces");
            return this;
        }

        /** Where on a face to click. {@link HitPoint#CENTRE} unless set. */
        public Builder hit(HitPoint hit) {
            this.hit = Validate.notNull(hit, "hit");
            return this;
        }

        /**
         * Whether your server accepts a click on nothing, placing into the cell
         * clicked: read live. Only when there is nothing beside the cell to click.
         * Off unless set.
         */
        public Builder airPlace(BooleanSupplier allowed) {
            this.airPlace = Validate.notNull(allowed, "allowed");
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public Clicks build() {
            StringBuilder missing = new StringBuilder();
            if (support == null) {
                missing.append(" support");
            }
            if (replaceable == null) {
                missing.append(" replaceable");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("Clicks need:" + missing);
            }
            return new Clicks(this);
        }
    }
}
