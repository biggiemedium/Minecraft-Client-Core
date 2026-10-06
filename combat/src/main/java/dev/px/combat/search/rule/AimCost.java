package dev.px.combat.search.rule;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.math.RotationMath;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * What turning to look at a point costs you, in damage: an option ranks by its
 * score less this. The search works out where you would look for each option
 * &mdash; {@code Option.getAim()} &mdash; and asks you what getting there costs;
 * how you turn is your rotation manager's business, whichever one it is.
 *
 * <pre>{@code
 * AimCost.NONE                                            // where you look does not matter: the default
 * AimCost.angle(rotations::getServerRotation,             // yours, from any rotation manager
 *         perDegree::getDouble, maxAngle::getDouble)      // each degree costs perDegree; past maxAngle, never
 * (eye, at) -> {                                          // anything else: ticks to turn, say
 *     int ticks = myRotations.ticksTo(eye.rotationTo(at));
 *     return ticks > 1 ? Double.POSITIVE_INFINITY : ticks * tickCost.getDouble();
 * }
 * }</pre>
 *
 * <ul>
 *   <li><b>A trade.</b> A cost of 3 means turning there is worth giving up 3
 *       damage: an option 3 damage weaker you are already looking at ranks the same.
 *       Make it large to have the quickest turn always win, damage only breaking
 *       ties.
 *   <li><b>A limit.</b> Infinity &mdash; or NaN, or anything else not finite
 *       &mdash; means you cannot turn there in time: the option is dropped before
 *       any damage is raycast.
 *   <li><b>A bonus.</b> A cost may be negative: keep to the spot you are already
 *       turning toward, say, rather than flipping between two nearly equal ones.
 * </ul>
 *
 * <p>The search stays exact whatever you return: a cost is worked out once per
 * spot or explosive, without raycasting, and the bound branch and bound uses is
 * lowered by it before spots are ordered. It is asked once for every spot that
 * passes the scan, so keep it cheap.
 */
@FunctionalInterface
public interface AimCost {

    /** Where you look never matters. */
    AimCost NONE = (eye, at) -> 0d;

    /**
     * @param eye where the search looks from: your eyes
     * @param at  where you would look to act on an option
     * @return what turning there is worth, in damage; not finite when you cannot
     */
    double cost(Vec3 eye, Vec3 at);

    /**
     * Costs {@code perDegree} damage for each degree between where you look now
     * and {@code at}, and drops anything further than {@code maxDegrees}. All
     * three are read on every search.
     *
     * @param looking where you look now, yaw and pitch, from whatever rotation
     *                manager you use: what the server last saw, say
     */
    static AimCost angle(Supplier<Vec2> looking, DoubleSupplier perDegree, DoubleSupplier maxDegrees) {
        Validate.notNull(looking, "looking");
        Validate.notNull(perDegree, "perDegree");
        Validate.notNull(maxDegrees, "maxDegrees");
        return (eye, at) -> {
            double degrees = RotationMath.difference(looking.get(), eye.rotationTo(at));
            return degrees > maxDegrees.getAsDouble() ? Double.POSITIVE_INFINITY : degrees * perDegree.getAsDouble();
        };
    }
}
