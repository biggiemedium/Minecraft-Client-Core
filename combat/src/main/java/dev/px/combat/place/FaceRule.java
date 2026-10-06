package dev.px.combat.place;

import dev.px.combat.search.rule.Reach;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.CellTest;
import dev.px.combat.world.Rays;
import dev.px.core.math.Box;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

/**
 * Which clicks your server accepts: a face of a block, clicked at a point, from
 * your eyes. Vanilla accepts any face; anticheats are stricter, each in its own
 * way, so the rule is yours, built from the checks they make:
 *
 * <pre>{@code
 * FaceRule.ANY                                               // vanilla
 * FaceRule.facingEye()                                       // "strict direction": only faces turned toward you
 *         .and(FaceRule.exposed(Game::isFullBlock))          // not covered by the block beside it
 *         .and(FaceRule.visible(blocks))                     // a clear line to the hit point
 *         .and(FaceRule.reach(Reach.of(4.5, 3), blocks))     // close enough, and closer still past a wall
 * }</pre>
 *
 * <p>Asked about every face of every block a planner considers; keep a rule of
 * your own cheap.
 */
@FunctionalInterface
public interface FaceRule {

    /** Every click is accepted: vanilla. */
    FaceRule ANY = (eye, block, face, hit) -> true;

    /**
     * @param eye   where you click from
     * @param block the block clicked
     * @param face  the face of it clicked
     * @param hit   where on the face, in world coordinates
     * @return whether the server accepts it
     */
    boolean allows(Vec3 eye, Vec3i block, Direction face, Vec3 hit);

    /** @return a rule that accepts only what this and {@code other} both accept */
    default FaceRule and(FaceRule other) {
        Validate.notNull(other, "other");
        FaceRule self = this;
        return (eye, block, face, hit) -> self.allows(eye, block, face, hit) && other.allows(eye, block, face, hit);
    }

    /**
     * Only a face turned toward you: your eyes beyond the plane it lies in, on its
     * outside. You cannot see the top of a block above your eyes, nor the far side
     * of one, and a strict server will not let you click them.
     */
    static FaceRule facingEye() {
        return (eye, block, face, hit) -> {
            switch (face) {
                case UP:
                    return eye.getY() > block.getY() + 1;
                case DOWN:
                    return eye.getY() < block.getY();
                case EAST:
                    return eye.getX() > block.getX() + 1;
                case WEST:
                    return eye.getX() < block.getX();
                case SOUTH:
                    return eye.getZ() > block.getZ() + 1;
                default:
                    return eye.getZ() < block.getZ();
            }
        };
    }

    /** Only a face not covered by the block beside it, as {@code solid} says. */
    static FaceRule exposed(CellTest solid) {
        Validate.notNull(solid, "solid");
        return (eye, block, face, hit) -> {
            Vec3i beside = block.add(face.toVec3i());
            return !solid.test(beside.getX(), beside.getY(), beside.getZ());
        };
    }

    /** Only a point with a clear line to it from your eyes, through {@code blocks}. */
    static FaceRule visible(BlockView blocks) {
        Validate.notNull(blocks, "blocks");
        return (eye, block, face, hit) -> {
            // Stop a hair short, outside the face, so the clicked block itself is never in the way.
            Vec3 toward = eye.subtract(hit);
            double length = toward.length();
            Vec3 end = length < 1e-9 ? hit : hit.add(toward.scale(1e-4d / length));
            return Rays.clear(eye, end, blocks);
        };
    }

    /**
     * Only a click within {@code reach}: the clicked block within its range,
     * measured as the reach measures, and past its wall range only with a clear
     * line to the hit point.
     */
    static FaceRule reach(Reach reach, BlockView blocks) {
        Validate.notNull(reach, "reach");
        Validate.notNull(blocks, "blocks");
        FaceRule visible = visible(blocks);
        return (eye, block, face, hit) -> {
            double distance = reach.distance(eye, Box.block(block.getX(), block.getY(), block.getZ()));
            return distance <= reach.range()
                    && (distance <= reach.wallRange() || visible.allows(eye, block, face, hit));
        };
    }
}
