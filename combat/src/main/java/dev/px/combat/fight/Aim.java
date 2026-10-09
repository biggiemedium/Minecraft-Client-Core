package dev.px.combat.fight;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.rotation.RotationRequest;
import dev.px.core.util.Validate;

/**
 * How the head turns in a {@link Fight}: from where it is to where the
 * {@link Attack} wants to look.
 *
 * <pre>{@code
 * // your rotation logic: smoothing, a turn limit, a little noise
 * Aim smooth = (b, point, wanted) -> {
 *     Vec2 next = mySmoothing.step(b.rotation(), wanted);
 *     return RotationRequest.at(next);
 * };
 *
 * Aim.snap();                                 // the default: straight there
 * Aim.limited(RotationRequest.at(Vec2.ZERO).step(40f));   // a template's step, hold and mode
 * }</pre>
 *
 * <p>The answer is a {@link RotationRequest}, filed at the flow's priority (the
 * request's own priority is replaced). Null leaves the head alone this tick.
 *
 * <p>A client-wide rotation manager does not need to drive: Core's
 * {@link dev.px.core.movement.rotation.RotationSink} is where Core's chosen
 * rotation reaches it. Drive only if the fight's turning itself must go
 * through your own code: then the step files nothing and {@link #turn} is yours
 * to act on.
 */
@FunctionalInterface
public interface Aim extends Part<Object> {

    /**
     * @param b      the fight this tick
     * @param point  where the attack wants to look
     * @param wanted the rotation that looks at {@code point} from the eyes
     * @return how to turn this tick; null to leave the head alone
     */
    RotationRequest turn(Bout<?> b, Vec3 point, Vec2 wanted);

    /** @return an aim straight at the point, each tick */
    static Aim snap() {
        return (b, point, wanted) -> RotationRequest.at(wanted);
    }

    /**
     * @return an aim that turns as {@code template} says &mdash; its step, hold
     *         and mode &mdash; towards the point
     */
    static Aim limited(RotationRequest template) {
        Validate.notNull(template, "template");
        return (b, point, wanted) -> template.retarget(wanted);
    }
}
