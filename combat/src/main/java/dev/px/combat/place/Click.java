package dev.px.combat.place;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;

import java.util.Locale;

/**
 * One way to place something: the block to click, which face of it, and where
 * on that face &mdash; what your client sends, and what it turns to first.
 *
 * <pre>{@code
 * Click click = clicks.best(eye, cell, looking);
 * rotations.lookAt(click.getRotation(eye));                          // turn to the hit point
 * Game.interact(click.getBlock(), click.getFace(), click.getHit());   // newer versions: the hit itself
 * Game.interact(click.getBlock(), click.getFace(), click.getRelativeHit());   // older: within the block
 * }</pre>
 *
 * <p>Immutable.
 */
public final class Click {

    private final Vec3i block;
    private final Direction face;
    private final Vec3 hit;
    private final Vec3i cell;
    private final boolean air;

    Click(Vec3i block, Direction face, Vec3 hit, Vec3i cell, boolean air) {
        this.block = block;
        this.face = face;
        this.hit = hit;
        this.cell = cell;
        this.air = air;
    }

    /** @return the block clicked */
    public Vec3i getBlock() {
        return block;
    }

    /** @return the face of it clicked */
    public Direction getFace() {
        return face;
    }

    /** @return where on that face, in world coordinates */
    public Vec3 getHit() {
        return hit;
    }

    /** @return the hit as an offset within the clicked block, 0 to 1 on each axis: what older versions send */
    public Vec3 getRelativeHit() {
        return hit.subtract(block.toVec3());
    }

    /** @return the cell something placed by this click goes into */
    public Vec3i getCell() {
        return cell;
    }

    /** @return whether it clicks the cell itself, against nothing: an air place, which only some servers accept */
    public boolean isAirPlace() {
        return air;
    }

    /** @return the yaw and pitch that look from {@code eye} at the hit point */
    public Vec2 getRotation(Vec3 eye) {
        return eye.rotationTo(hit);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Click)) {
            return false;
        }
        Click click = (Click) other;
        return click.block.equals(block) && click.face == face && click.hit.equals(hit) && click.cell.equals(cell)
                && click.air == air;
    }

    @Override
    public int hashCode() {
        return (block.hashCode() * 31 + face.hashCode()) * 31 + hit.hashCode();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Click(%s %s at %.3f, %.3f, %.3f%s)", block, face, hit.getX(), hit.getY(),
                hit.getZ(), air ? ", air" : "");
    }
}
