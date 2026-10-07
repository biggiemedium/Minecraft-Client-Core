package dev.px.projectile;

/**
 * How much of the shooter's own movement a projectile carries away with it.
 *
 * <pre>{@code
 * LaunchRules.builder().shooterVelocity(ShooterVelocity.ALL_BUT_VERTICAL_ON_GROUND) ...
 * }</pre>
 *
 * <p>The <a href="https://minecraft.wiki/w/Projectile#Throwable_projectiles_(except_eye_of_ender)">wiki</a>
 * says a thrown projectile gets the player's velocity added, all but the
 * vertical part when the player is on the ground. It says a bow's arrow depends
 * on "the user's movement speed" without saying how. Which applies is your
 * game's to say.
 */
public enum ShooterVelocity {

    /** The projectile leaves at its own velocity alone. */
    NONE,
    /** The shooter's velocity is added. */
    ALL,
    /** The shooter's velocity is added, except the vertical part while they are on the ground. */
    ALL_BUT_VERTICAL_ON_GROUND
}
