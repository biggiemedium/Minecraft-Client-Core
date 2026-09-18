package dev.px.core.movement;

import dev.px.core.math.MathUtil;
import dev.px.core.util.Validate;
import dev.px.core.util.math.MovementMath;
import lombok.Getter;

/**
 * Keeps the player walking where they meant to when something else has turned
 * their head.
 *
 * <p>Minecraft derives motion from the key input rotated by the player's yaw. So
 * the moment a rotation reaches the movement code &mdash; an aim that turns 90
 * degrees to face a target while the player holds forward &mdash; the game walks
 * them 90 degrees off, into the wall or off the edge. This solves for the input
 * that cancels that out: the keys that, at the new yaw, produce the motion the
 * old yaw and the player's actual keys would have.
 *
 * <pre>{@code
 * // in the adapter, where moveForward / moveStrafing are read
 * float applied = Core.rotations().getRotation().getYaw();
 * MovementCorrection.Input fixed =
 *         MovementCorrection.correct(cameraYaw, moveForward, moveStrafing, applied);
 * moveForward  = fixed.getForward();
 * moveStrafing = fixed.getStrafe();
 * }</pre>
 *
 * <p><b>Whether you need this depends on your sink.</b> A
 * {@link dev.px.core.movement.rotation.RotationMode#SILENT} implementation that
 * writes only into the outgoing packet leaves the game's own yaw alone, so motion
 * was never wrong and there is nothing to correct. One that sets the yaw field
 * around the movement update &mdash; the usual technique &mdash; does need it.
 * Core cannot tell which you wrote, so it supplies the arithmetic and stays out
 * of the decision.
 *
 * <h2>Why there are two modes</h2>
 *
 * <p>Vanilla input is quantised. {@code moveForward} and {@code moveStrafing} are
 * each -1, 0 or +1 before multipliers, which is eight directions and no more. The
 * direction that would cancel a rotation exactly is usually not one of the eight.
 *
 * <ul>
 *   <li>{@link Mode#STRICT} rounds to the nearest of the eight. Every value it
 *       produces is one a vanilla client could have sent, at the cost of up to
 *       22.5&deg; of error &mdash; the player drifts slightly, but nothing about
 *       the input looks synthetic.
 *   <li>{@link Mode#EXACT} emits the fractional pair that reproduces the
 *       direction precisely. The player goes exactly where they intended, and the
 *       input values are ones no vanilla client ever sends.
 * </ul>
 *
 * <p>There is no right answer, which is why it is an argument. {@link Mode#STRICT}
 * is the default because the failure it causes is a small drift rather than a
 * property that cannot be explained.
 *
 * <p><b>Complexity.</b> O(1), and allocation is one {@link Input} per call.
 */
public final class MovementCorrection {

    /** Input magnitude the game uses for a held key. */
    private static final float HELD = 1f;

    /** Below this, the stick is centred and there is nothing to correct. */
    private static final double DEAD_ZONE = 1.0E-4d;

    private MovementCorrection() {
    }

    /** How faithfully the corrected input has to look like something a keyboard produced. */
    public enum Mode {

        /**
         * Round to the eight directions a keyboard can express.
         *
         * <p>Costs up to 22.5&deg; of accuracy and produces only values vanilla
         * also produces.
         */
        STRICT,

        /**
         * Reproduce the direction exactly with fractional input.
         *
         * <p>Accurate, and distinguishable from a real keyboard by anything that
         * looks at the values.
         */
        EXACT
    }

    /**
     * Corrects input for a rotation, rounding to the eight keyboard directions.
     *
     * @param intendedYaw the yaw the player is steering by &mdash; the camera yaw,
     *        which is where they think they are facing
     * @param forward the player's own forward input, positive walking forward
     * @param strafe the player's own strafe input, <b>positive walking left</b>,
     *        matching the game's field
     * @param appliedYaw the yaw the game will actually use this tick
     * @return input to use instead, at {@code appliedYaw}
     */
    public static Input correct(float intendedYaw, double forward, double strafe, float appliedYaw) {
        return correct(intendedYaw, forward, strafe, appliedYaw, Mode.STRICT);
    }

    /** Corrects input for a rotation. See {@link Mode} for the trade-off. */
    public static Input correct(float intendedYaw, double forward, double strafe,
                                float appliedYaw, Mode mode) {
        Validate.notNull(mode, "mode");
        if (!isSignificant(forward) && !isSignificant(strafe)) {
            // Standing still. Correcting nothing into something would make the
            // player walk on their own, which is worse than the bug being fixed.
            return Input.NONE;
        }

        // Where the player actually wants to travel, which is only the yaw they
        // face when walking straight forward.
        float travelYaw = MovementMath.movementYaw(intendedYaw, forward, strafe);

        // ...expressed relative to the yaw the game is about to use.
        float relative = MathUtil.angleDifference(appliedYaw, travelYaw);

        return mode == Mode.EXACT ? exact(relative) : strict(relative);
    }

    /**
     * @return whether correcting for this rotation would change the input at all
     *
     * <p>The two yaws being within a rounding step of each other means
     * {@link Mode#STRICT} would hand back what it was given. Worth checking before
     * writing the fields, if writing them is itself observable.
     */
    public static boolean isNeeded(float intendedYaw, float appliedYaw) {
        return Math.abs(MathUtil.angleDifference(intendedYaw, appliedYaw)) > 22.5f;
    }

    /**
     * Rounds the travel direction to the nearest eighth and reads off the keys.
     *
     * <p>Forward and strafe are the signs of the components of that eighth: due
     * left is {@code (0, +1)}, back-right is {@code (-1, -1)}. The sign
     * convention is the game's, so strafe is positive walking left.
     */
    private static Input strict(float relative) {
        int eighth = Math.round(relative / 45f);
        // -180 and 180 are the same direction; fold so the table below is 0..7.
        eighth = ((eighth % 8) + 8) % 8;

        switch (eighth) {
            case 0: return new Input(HELD, 0f);          // straight ahead
            case 1: return new Input(HELD, -HELD);       // ahead and right
            case 2: return new Input(0f, -HELD);         // right
            case 3: return new Input(-HELD, -HELD);      // back and right
            case 4: return new Input(-HELD, 0f);         // back
            case 5: return new Input(-HELD, HELD);       // back and left
            case 6: return new Input(0f, HELD);          // left
            default: return new Input(HELD, HELD);       // ahead and left
        }
    }

    /**
     * Solves the rotation exactly.
     *
     * <p>The inverse of the game's own transform: forward is the component along
     * the applied yaw and strafe the component across it, negated because strafe
     * counts positive to the left.
     */
    private static Input exact(float relative) {
        double radians = Math.toRadians(relative);
        return new Input((float) Math.cos(radians), (float) -Math.sin(radians));
    }

    private static boolean isSignificant(double input) {
        return Math.abs(input) > DEAD_ZONE;
    }

    /**
     * A corrected pair of input values.
     *
     * <p>A value rather than two out-parameters because they are only ever
     * meaningful together: applying one without the other steers the player
     * somewhere neither yaw intended.
     */
    @Getter
    public static final class Input {

        /** No keys held. */
        public static final Input NONE = new Input(0f, 0f);

        private final float forward;
        private final float strafe;

        Input(float forward, float strafe) {
            this.forward = forward;
            this.strafe = strafe;
        }

        /** @return whether either axis is pressed. */
        public boolean isMoving() {
            return forward != 0f || strafe != 0f;
        }

        /**
         * @return the same input scaled, for the multipliers the game applies on
         *         top &mdash; 0.3 while sneaking, 0.2 while using an item
         */
        public Input scaled(float factor) {
            return new Input(forward * factor, strafe * factor);
        }

        @Override
        public String toString() {
            return "Input(forward=" + forward + ", strafe=" + strafe + ")";
        }
    }
}
