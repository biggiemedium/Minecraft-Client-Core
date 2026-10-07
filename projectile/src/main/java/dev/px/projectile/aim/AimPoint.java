package dev.px.projectile.aim;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

/**
 * Where on a target to aim.
 *
 * <pre>{@code
 * AimPoint.CENTRE                                    // the middle of its box: the most room either way
 * AimPoint.EYES                                      // its eyes
 * AimPoint.height(0.75)                              // three quarters of the way up its box
 * (target, eye) -> target.aimPoint(eye, 0.1)          // anything else
 * }</pre>
 *
 * <p>Asked about the target where the solver expects it to be when the
 * projectile arrives, not where it is now.
 */
@FunctionalInterface
public interface AimPoint {

    /**
     * @param target the target, where it will be
     * @param eye    the shooter's eyes
     * @return the point the projectile should pass through
     */
    Vec3 on(Tracked<?> target, Vec3 eye);

    /** The middle of its box. */
    AimPoint CENTRE = (target, eye) -> target.getCenter();

    /** Its eyes. */
    AimPoint EYES = (target, eye) -> target.getEyePosition();

    /** @return the point {@code fraction} of the way up its box, in the middle: 0 its feet, 1 its top */
    static AimPoint height(double fraction) {
        Validate.check(fraction >= 0d && fraction <= 1d, "the height is a fraction from 0 to 1");
        return (target, eye) -> {
            Vec3 centre = target.getCenter();
            return Vec3.of(centre.getX(), target.getMinY() + target.getHeight() * fraction, centre.getZ());
        };
    }
}
