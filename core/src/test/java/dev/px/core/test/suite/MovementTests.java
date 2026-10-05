package dev.px.core.test.suite;

import dev.px.core.math.Vec3;
import dev.px.core.movement.MovementCorrection;
import dev.px.core.test.harness.Checks;
import dev.px.core.util.math.MovementMath;

/**
 * Movement correction: keeping the player walking where they meant to after
 * something has turned their head.
 *
 * <p>The property worth testing is not what numbers come out, it is that the
 * motion they produce at the new yaw matches the motion the player's own keys
 * would have produced at the old one. So the checks below run the corrected input
 * back through {@link MovementMath#velocity} &mdash; the same function the game
 * uses &mdash; and compare directions.
 *
 * <p>That is also the only way to test {@link MovementCorrection.Mode#STRICT}
 * honestly. It rounds to the eight directions a keyboard can express, so it is
 * allowed to be wrong, but never by more than half of one eighth.
 */
public final class MovementTests {

    /** Half of one eighth of a circle: the most STRICT rounding can cost. */
    private static final double STRICT_TOLERANCE = 22.5d;

    private MovementTests() {
    }

    public static void run() {
        Checks.section("Movement correction");

        // ---- the basics ----------------------------------------------------
        Checks.check("no keys held corrects to no keys",
                !MovementCorrection.correct(0f, 0d, 0d, 90f).isMoving());

        MovementCorrection.Input unchanged = MovementCorrection.correct(45f, 1d, 0d, 45f);
        Checks.checkEquals("an unrotated forward is left alone", 1f, unchanged.getForward());
        Checks.checkEquals("with no strafe introduced", 0f, unchanged.getStrafe());

        // Turned 90 degrees right while walking forward: the keys have to become
        // "strafe left" or the player walks off at a right angle to their intent.
        MovementCorrection.Input turned = MovementCorrection.correct(0f, 1d, 0d, 90f);
        Checks.checkEquals("a 90 degree turn puts the forward key onto strafe",
                0f, turned.getForward());
        Checks.checkEquals("in the direction that cancels the turn", 1f, turned.getStrafe());

        // ---- the property, over the whole circle ----------------------------
        // Every combination of a facing, a key pair and an applied rotation must
        // still travel where the player asked, to within what the mode allows.
        double worstStrict = 0d;
        double worstExact = 0d;
        boolean strictValuesAreKeyboardLike = true;

        double[][] keys = { { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 }, { -1, 0 }, { -1, -1 },
                            { 0, -1 }, { 1, -1 } };
        for (float intended = -180f; intended < 180f; intended += 17f) {
            for (float applied = -180f; applied < 180f; applied += 13f) {
                for (double[] key : keys) {
                    Vec3 wanted = MovementMath.velocity(intended, key[0], key[1], 1d);

                    MovementCorrection.Input strict =
                            MovementCorrection.correct(intended, key[0], key[1], applied);
                    worstStrict = Math.max(worstStrict, offBy(wanted, applied, strict));
                    strictValuesAreKeyboardLike &= isKeyboardLike(strict);

                    MovementCorrection.Input exact = MovementCorrection.correct(
                            intended, key[0], key[1], applied, MovementCorrection.Mode.EXACT);
                    worstExact = Math.max(worstExact, offBy(wanted, applied, exact));
                }
            }
        }

        Checks.check("strict correction is never off by more than half a key step"
                        + "  (worst " + Math.round(worstStrict) + " degrees)",
                worstStrict <= STRICT_TOLERANCE + 0.01d);
        Checks.check("and only ever emits values a keyboard could produce",
                strictValuesAreKeyboardLike);
        Checks.check("exact correction reproduces the direction"
                        + "  (worst " + String.format("%.4f", worstExact) + " degrees)",
                worstExact < 0.01d);

        // ---- the helpers ----------------------------------------------------
        Checks.check("a rotation inside one key step needs no correction",
                !MovementCorrection.isNeeded(0f, 20f));
        Checks.check("a larger one does", MovementCorrection.isNeeded(0f, 45f));
        Checks.check("measured the short way round 180",
                MovementCorrection.isNeeded(170f, -170f) == false);

        MovementCorrection.Input sneaking = MovementCorrection.correct(0f, 1d, 0d, 0f).scaled(0.3f);
        Checks.checkEquals("input scales for the multipliers the game applies on top",
                0.3f, sneaking.getForward());
        Checks.check("nothing held is still nothing held after scaling",
                !MovementCorrection.Input.NONE.isMoving());
    }

    /**
     * @return how far, in degrees, the corrected input actually travels from where
     *         the player asked to go
     */
    private static double offBy(Vec3 wanted, float appliedYaw, MovementCorrection.Input corrected) {
        Vec3 got = MovementMath.velocity(appliedYaw, corrected.getForward(), corrected.getStrafe(), 1d);
        if (wanted.length() < 1.0E-6d || got.length() < 1.0E-6d) {
            return 0d;
        }
        double cosine = wanted.normalize().dot(got.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1d, Math.min(1d, cosine))));
    }

    /** @return whether both axes are one of the three values a key can be in. */
    private static boolean isKeyboardLike(MovementCorrection.Input input) {
        return isKey(input.getForward()) && isKey(input.getStrafe());
    }

    private static boolean isKey(float value) {
        return value == -1f || value == 0f || value == 1f;
    }
}
