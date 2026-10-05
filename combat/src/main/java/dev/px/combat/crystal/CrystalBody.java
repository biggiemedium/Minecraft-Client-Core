package dev.px.combat.crystal;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

/**
 * Where a placed crystal ends up, how big it is, and where it explodes from:
 * your game's numbers, kept in one place.
 *
 * <pre>{@code
 * CrystalBody body = CrystalBody.at(0.5, 1, 0.5)   // its position, from the base block's corner
 *         .size(2, 2)                              // its hitbox: width and height
 *         .explodingAt(0, 0, 0);                   // the explosion, from its position
 * }</pre>
 *
 * <p>A crystal's position is the bottom centre of its hitbox, the same as any
 * entity Core tracks, so a crystal already in the world can be measured from the
 * {@code Tracked} the trackers hold.
 *
 * <p>Immutable.
 */
public final class CrystalBody {

    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final double width;
    private final double height;
    private final double explosionX;
    private final double explosionY;
    private final double explosionZ;
    private final boolean sized;
    private final boolean exploding;

    private CrystalBody(double offsetX, double offsetY, double offsetZ, double width, double height,
                        double explosionX, double explosionY, double explosionZ, boolean sized, boolean exploding) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.width = width;
        this.height = height;
        this.explosionX = explosionX;
        this.explosionY = explosionY;
        this.explosionZ = explosionZ;
        this.sized = sized;
        this.exploding = exploding;
    }

    /** @return a body whose position is this far from the base block's lowest corner */
    public static CrystalBody at(double offsetX, double offsetY, double offsetZ) {
        return new CrystalBody(offsetX, offsetY, offsetZ, 0d, 0d, 0d, 0d, 0d, false, false);
    }

    /** Required: the hitbox's width and height. */
    public CrystalBody size(double width, double height) {
        Validate.check(width >= 0d && height >= 0d, "a size must not be negative");
        return new CrystalBody(offsetX, offsetY, offsetZ, width, height,
                explosionX, explosionY, explosionZ, true, exploding);
    }

    /** Required: where the explosion starts, from the crystal's position. */
    public CrystalBody explodingAt(double x, double y, double z) {
        return new CrystalBody(offsetX, offsetY, offsetZ, width, height, x, y, z, sized, true);
    }

    /** @return whether every part has been given */
    public boolean isComplete() {
        return sized && exploding;
    }

    /** @return where a crystal placed on this base block would be */
    public Vec3 position(int baseX, int baseY, int baseZ) {
        return Vec3.of(baseX + offsetX, baseY + offsetY, baseZ + offsetZ);
    }

    /** @return the hitbox a crystal placed on this base block would have */
    public Box box(int baseX, int baseY, int baseZ) {
        return Box.around(position(baseX, baseY, baseZ), width, height);
    }

    /** @return where a crystal placed on this base block would explode from */
    public Vec3 explosion(int baseX, int baseY, int baseZ) {
        return explosionOf(position(baseX, baseY, baseZ));
    }

    /** @return where a crystal at {@code position} explodes from */
    public Vec3 explosionOf(Vec3 position) {
        return position.add(explosionX, explosionY, explosionZ);
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    @Override
    public String toString() {
        return "CrystalBody(at " + offsetX + ", " + offsetY + ", " + offsetZ + "; " + width + "x" + height
                + "; explodes at +" + explosionX + ", " + explosionY + ", " + explosionZ + ")";
    }
}
