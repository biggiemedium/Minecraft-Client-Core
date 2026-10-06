package dev.px.combat.place;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;

/**
 * Where on a face to click.
 *
 * <pre>{@code
 * HitPoint.CENTRE                      // the middle of the face: what most clients send
 * HitPoint.NEAREST                     // the point of the face nearest your eyes: the shortest reach
 * }</pre>
 */
@FunctionalInterface
public interface HitPoint {

    /** The middle of the face. */
    HitPoint CENTRE = (eye, block, face) -> {
        Vec3 middle = block.center();
        return middle.add(face.toVector().scale(0.5d));
    };

    /** The point of the face nearest your eyes, kept a little inside its edges. */
    HitPoint NEAREST = (eye, block, face) -> {
        double inset = 1e-3d;
        double x = clamp(eye.getX(), block.getX() + inset, block.getX() + 1 - inset);
        double y = clamp(eye.getY(), block.getY() + inset, block.getY() + 1 - inset);
        double z = clamp(eye.getZ(), block.getZ() + inset, block.getZ() + 1 - inset);
        switch (face) {
            case UP:
                y = block.getY() + 1;
                break;
            case DOWN:
                y = block.getY();
                break;
            case EAST:
                x = block.getX() + 1;
                break;
            case WEST:
                x = block.getX();
                break;
            case SOUTH:
                z = block.getZ() + 1;
                break;
            default:
                z = block.getZ();
                break;
        }
        return Vec3.of(x, y, z);
    };

    /** @return where on {@code face} of {@code block} to click, from {@code eye}, in world coordinates */
    Vec3 on(Vec3 eye, Vec3i block, Direction face);

    /** Kept here so the constants above need nothing outside the interface. */
    static double clamp(double value, double low, double high) {
        return value < low ? low : value > high ? high : value;
    }
}
