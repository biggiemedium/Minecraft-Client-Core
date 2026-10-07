package dev.px.projectile;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;

/**
 * Where a projectile appears, relative to the eyes of whoever launches it.
 *
 * <pre>{@code
 * LaunchOrigin.EYE                                      // at the eyes
 * LaunchOrigin.below(0.1)                               // a little under them
 * (eye, rotation) -> myOwnOffset(eye, rotation)          // anything else
 * }</pre>
 *
 * <p>The <a href="https://minecraft.wiki/w/Projectile#Initial_conditions">wiki</a>
 * gives where a dispenser puts a projectile but not where a player's appears, so
 * it is yours to say.
 */
@FunctionalInterface
public interface LaunchOrigin {

    /**
     * @param eye      the shooter's eye position
     * @param rotation where they look: yaw and pitch, before any pitch offset
     * @return where the projectile starts
     */
    Vec3 from(Vec3 eye, Vec2 rotation);

    /** At the eyes. */
    LaunchOrigin EYE = (eye, rotation) -> eye;

    /** @return {@code blocks} straight down from the eyes */
    static LaunchOrigin below(double blocks) {
        return (eye, rotation) -> eye.add(0d, -blocks, 0d);
    }
}
