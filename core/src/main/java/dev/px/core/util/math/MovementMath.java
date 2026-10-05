package dev.px.core.util.math;

import dev.px.core.math.MathUtil;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

/**
 * Movement maths: turning key input into motion, and motion into readable numbers.
 *
 * <p>The arithmetic every movement module rewrites &mdash; speed, longjump, fly,
 * strafe, a BPS readout &mdash; usually slightly differently, and usually with a
 * diagonal-speed bug in it. It is pure trigonometry over plain doubles, so it
 * belongs here rather than in the version adapter: the adapter's job is to read
 * {@code moveForward} and write {@code motionX}, not to know what happens in
 * between.
 *
 * <p><b>Conventions,</b> matching the game so an adapter can pass its fields
 * straight through:
 * <ul>
 *   <li>Yaw is degrees, 0 facing +Z, increasing clockwise when viewed from above.</li>
 *   <li>Forward input is positive walking forward, negative walking back.</li>
 *   <li><b>Strafe input is positive walking left</b>, which looks wrong and is
 *       correct: it is the sign the game's own strafe field uses.</li>
 *   <li>Motion and speed are per tick. Multiply by {@link #TICKS_PER_SECOND} for
 *       anything a user reads.</li>
 * </ul>
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>Gravity, drag, friction, walking speed and the effect multipliers are not
 * constants here. They are a {@link PhysicsProfile}, because they are one game
 * version's values rather than universal ones and a client on a version where
 * they differ needs to be able to say so. Every method that needs them comes in
 * two forms: one that takes a profile, and one that uses {@link #getProfile()}.
 *
 * <pre>{@code
 * double speed = MovementMath.walkSpeed(2, 0);                 // the default profile
 * double ice   = MovementMath.friction(slippery, motion, 1d);  // an explicit one
 *
 * MovementMath.setProfile(PhysicsProfile.vanilla().withWalkSpeed(0.2806d));  // client-wide
 * }</pre>
 *
 * <p>{@link #TICKS_PER_SECOND} stays a constant because it is not physics: it is
 * how the protocol defines a tick, and a server running slower is lag rather than
 * a different rule.
 *
 * <p><b>Complexity.</b> Everything is O(1) except the three predictors, which
 * simulate a tick at a time and are O(ticks).
 *
 * <p>Nothing here imports or needs the game.
 *
 * @see PhysicsProfile for the numbers and what is deliberately not among them
 * @see RotationMath for where the player is looking
 */
public final class MovementMath {

    /** Ticks in a second on an unlagged server. The conversion behind every BPS readout. */
    public static final double TICKS_PER_SECOND = 20d;

    /**
     * The profile used by every overload that does not take one.
     *
     * <p>Volatile because an adapter may set it during startup while a background
     * module is already reading it. Set once, early; changing it mid-session is
     * legal but means two modules can disagree about gravity within a tick.
     */
    private static volatile PhysicsProfile profile = PhysicsProfile.vanilla();

    private MovementMath() {
    }

    /** @return the profile the no-profile overloads use. Never null. */
    public static PhysicsProfile getProfile() {
        return profile;
    }

    /** Replaces the default profile. Passing null restores {@link PhysicsProfile#vanilla()}. */
    public static void setProfile(PhysicsProfile replacement) {
        profile = replacement == null ? PhysicsProfile.vanilla() : replacement;
    }

    // ------------------------------------------------------------- direction

    /**
     * @return the horizontal motion produced by walking at {@code speed}, as a
     *         vector whose Y is zero
     *
     * <pre>
     * k = speed / max(1, √(f² + s²))
     * x = -sin(yaw)·f·k + cos(yaw)·s·k
     * z =  cos(yaw)·f·k + sin(yaw)·s·k
     * </pre>
     *
     * <p>Diagonal input is normalised first, so holding forward and left moves at
     * {@code speed} rather than {@code speed * 1.41}. Skipping that step is the
     * single most common bug in a hand-rolled speed module, and the most visible
     * one to anti-cheat.
     *
     * <p>Takes the speed rather than reading it from a profile, because the caller
     * is usually the one deciding it &mdash; sprinting, an item modifier, or the
     * module's own setting.
     */
    public static Vec3 velocity(float yaw, double forward, double strafe, double speed) {
        double magnitude = forward * forward + strafe * strafe;
        if (magnitude < 1.0E-4d) {
            return Vec3.ZERO;
        }
        double scale = speed / Math.max(1d, Math.sqrt(magnitude));
        double scaledForward = forward * scale;
        double scaledStrafe = strafe * scale;

        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return Vec3.of(
                scaledForward * -sin + scaledStrafe * cos,
                0d,
                scaledForward * cos + scaledStrafe * sin);
    }

    /** @return the horizontal offset {@code distance} blocks ahead of {@code yaw}. */
    public static Vec3 forward(float yaw, double distance) {
        double radians = Math.toRadians(yaw);
        return Vec3.of(-Math.sin(radians) * distance, 0d, Math.cos(radians) * distance);
    }

    /**
     * @return the yaw the player is actually travelling along, which is only the
     *         yaw they are facing when walking straight forward
     *
     * <pre>
     * yaw' = yaw + (f &lt; 0 ? 180 : 0) - sign(s) · (f = 0 ? 90 : 45 · sign(f))
     * </pre>
     *
     * <p>What a movement module steers by: strafing left while facing north means
     * moving west, and a module that accelerates along the facing yaw instead
     * will visibly drift.
     */
    public static float movementYaw(float facingYaw, double forward, double strafe) {
        if (forward == 0d && strafe == 0d) {
            return MathUtil.wrapDegrees(facingYaw);
        }
        float yaw = facingYaw;
        if (forward < 0d) {
            yaw += 180f;
        }
        // Straight sideways turns a full 90 degrees; a diagonal only 45.
        float quarter = forward == 0d ? 90f : forward > 0d ? 45f : -45f;
        if (strafe > 0d) {
            yaw -= quarter;
        } else if (strafe < 0d) {
            yaw += quarter;
        }
        return MathUtil.wrapDegrees(yaw);
    }

    /** @return whether any directional key is held. */
    public static boolean isMoving(double forward, double strafe) {
        return forward != 0d || strafe != 0d;
    }

    // ----------------------------------------------------------------- speed

    /** @return horizontal speed in blocks per tick, ignoring vertical motion. */
    public static double speed(double motionX, double motionZ) {
        return Math.sqrt(motionX * motionX + motionZ * motionZ);
    }

    public static double speed(Vec3 motion) {
        return speed(motion.getX(), motion.getZ());
    }

    /** @return horizontal speed in blocks per second. The number a HUD shows. */
    public static double blocksPerSecond(double motionX, double motionZ) {
        return speed(motionX, motionZ) * TICKS_PER_SECOND;
    }

    public static double blocksPerSecond(Vec3 motion) {
        return blocksPerSecond(motion.getX(), motion.getZ());
    }

    /** Walking speed with effects applied, using the default profile. */
    public static double walkSpeed(int speedAmplifier, int slownessAmplifier) {
        return walkSpeed(profile, speedAmplifier, slownessAmplifier);
    }

    /**
     * @param speedAmplifier Speed level held, 0 for none (so Speed II is 2)
     * @param slownessAmplifier Slowness level held, 0 for none
     * @return walking speed in blocks per tick with those effects applied
     *
     * <pre>
     * speed = walkSpeed · (1 + speedPerLevel·speedLevel) · (1 - slownessPerLevel·slownessLevel)
     * </pre>
     *
     * <p>Amplifiers are levels as a player reads them, not the zero-based numbers
     * the game stores. Convert at the adapter, where the off-by-one is visible.
     */
    public static double walkSpeed(PhysicsProfile profile, int speedAmplifier, int slownessAmplifier) {
        Validate.notNull(profile, "profile");
        double speed = profile.getWalkSpeed()
                * (1d + profile.getSpeedPerLevel() * Math.max(0, speedAmplifier));
        speed *= 1d - profile.getSlownessPerLevel() * Math.max(0, slownessAmplifier);
        return Math.max(0d, speed);
    }

    /** @return the upward velocity a jump starts with, given a Jump Boost level. */
    public static double jumpVelocity(int jumpBoostAmplifier) {
        return jumpVelocity(profile, jumpBoostAmplifier);
    }

    public static double jumpVelocity(PhysicsProfile profile, int jumpBoostAmplifier) {
        Validate.notNull(profile, "profile");
        return profile.getJumpVelocity()
                + profile.getJumpBoostPerLevel() * Math.max(0, jumpBoostAmplifier);
    }

    // ------------------------------------------------------------ prediction

    /** Vertical velocity one tick later, using the default profile. */
    public static double fall(double motionY) {
        return fall(profile, motionY);
    }

    /**
     * @return vertical velocity one tick later, gravity and drag applied:
     *         {@code v' = (v - gravity) · drag}
     */
    public static double fall(PhysicsProfile profile, double motionY) {
        Validate.notNull(profile, "profile");
        return (motionY - profile.getGravity()) * profile.getDrag();
    }

    /** Horizontal velocity one tick later, using the default profile. */
    public static double friction(double motion, double slipperiness) {
        return friction(profile, motion, slipperiness);
    }

    /**
     * @return horizontal velocity one tick later on a surface of that
     *         slipperiness: {@code v' = v · slipperiness · groundFriction}
     *
     * <p>Slipperiness stays an argument rather than joining the profile: it is a
     * property of the block underfoot, not of the game version, and it changes
     * every time the player steps off ice.
     */
    public static double friction(PhysicsProfile profile, double motion, double slipperiness) {
        Validate.notNull(profile, "profile");
        return motion * slipperiness * profile.getGroundFriction();
    }

    /** How far the player falls over {@code ticks}, using the default profile. */
    public static double fallDistance(double motionY, int ticks) {
        return fallDistance(profile, motionY, ticks);
    }

    /**
     * @return how far the player falls over {@code ticks}, starting from
     *         {@code motionY}. Negative is downward, matching motion
     *
     * <p>O(ticks), iterating {@code v ← (v - gravity) · drag} and accumulating.
     *
     * <p>Simulated rather than solved, because the closed form for a geometric
     * series with drag is easy to get subtly wrong and this runs a handful of
     * iterations at most. Ignores collision: it answers where the player would be
     * in open air, which is what a fall-damage warning or a scaffold needs.
     */
    public static double fallDistance(PhysicsProfile profile, double motionY, int ticks) {
        Validate.notNull(profile, "profile");
        double velocity = motionY;
        double travelled = 0d;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = fall(profile, velocity);
            travelled += velocity;
        }
        return travelled;
    }

    /** Ticks of free fall before reaching {@code speed}, using the default profile. */
    public static int ticksToFallSpeed(double speed) {
        return ticksToFallSpeed(profile, speed);
    }

    /** @return ticks of free fall before reaching {@code speed} downward. */
    public static int ticksToFallSpeed(PhysicsProfile profile, double speed) {
        Validate.notNull(profile, "profile");
        double velocity = 0d;
        int ticks = 0;
        while (-velocity < Math.abs(speed) && ticks < 1000) {
            velocity = fall(profile, velocity);
            ticks++;
        }
        return ticks;
    }

    /** Where unimpeded motion ends up after {@code ticks}, using the default profile. */
    public static Vec3 predict(Vec3 position, Vec3 motion, int ticks) {
        return predict(profile, position, motion, ticks);
    }

    /**
     * @return where {@code position} ends up after {@code ticks} of unimpeded
     *         motion, gravity and air drag applied to the vertical component
     *
     * <p>O(ticks).
     *
     * <p>Horizontal motion is treated as constant, which is true in the air and
     * close enough on the ground for the tick or two a prediction usually spans.
     * There is no collision here; this is a trajectory, not a simulation.
     */
    public static Vec3 predict(PhysicsProfile profile, Vec3 position, Vec3 motion, int ticks) {
        Validate.notNull(profile, "profile");
        double x = position.getX();
        double y = position.getY();
        double z = position.getZ();
        double velocityY = motion.getY();
        for (int tick = 0; tick < ticks; tick++) {
            x += motion.getX();
            z += motion.getZ();
            velocityY = fall(profile, velocityY);
            y += velocityY;
        }
        return Vec3.of(x, y, z);
    }
}
