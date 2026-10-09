package dev.px.combat.fight;

import dev.px.core.control.Click;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

/**
 * What an {@link Attack} wants this tick: where to look, and which button to
 * click or hold.
 *
 * <pre>{@code
 * Strike.none()                                         // nothing this tick
 * Strike.at(target.getCenter())                         // look, but do not swing
 * Strike.at(aim).click(Click.ATTACK)                    // look and swing
 *         .whenFacing(target.getBox())                  // only once the head is on the box
 * Strike.at(base).hold(Click.USE)                       // look and hold use
 * Strike.failed("no crystals left")                     // end the fight as failed
 * }</pre>
 *
 * <p>The fight step turns the head through the {@link Aim}, then clicks if the
 * head, as it is turned this tick, looks at the {@link #whenFacing} box from the
 * eyes. With no box it clicks regardless. How far is in reach is the attack's to
 * check before it asks.
 *
 * <p>Immutable.
 */
public final class Strike {

    private static final Strike NONE = new Strike(null, null, false, null, null);

    private final Vec3 aim;
    private final Click button;
    private final boolean held;
    private final Box facing;
    private final String failure;

    private Strike(Vec3 aim, Click button, boolean held, Box facing, String failure) {
        this.aim = aim;
        this.button = button;
        this.held = held;
        this.facing = facing;
        this.failure = failure;
    }

    /** @return a strike that does nothing */
    public static Strike none() {
        return NONE;
    }

    /** @return a strike that looks at {@code point} and clicks nothing, until told to */
    public static Strike at(Vec3 point) {
        return new Strike(Validate.notNull(point, "point"), null, false, null, null);
    }

    /** @return a strike that ends the fight as failed, with {@code reason} */
    public static Strike failed(String reason) {
        return new Strike(null, null, false, null, Validate.notBlank(reason, "reason"));
    }

    /** @return this strike, clicking {@code button} once this tick */
    public Strike click(Click button) {
        return new Strike(aim, Validate.notNull(button, "button"), false, facing, failure);
    }

    /** @return this strike, holding {@code button} down this tick */
    public Strike hold(Click button) {
        return new Strike(aim, Validate.notNull(button, "button"), true, facing, failure);
    }

    /** @return this strike, clicking only if the head looks at {@code box} this tick */
    public Strike whenFacing(Box box) {
        return new Strike(aim, button, held, Validate.notNull(box, "box"), failure);
    }

    /** @return where to look; null to leave the head alone */
    public Vec3 getAim() {
        return aim;
    }

    /** @return the button to click or hold; null for none */
    public Click getButton() {
        return button;
    }

    /** @return whether the button is held rather than clicked */
    public boolean isHeld() {
        return held;
    }

    /** @return the box the head must look at for the click to go; null if it need not */
    public Box getFacing() {
        return facing;
    }

    /** @return why the fight failed; null for a strike that did not fail it */
    public String getFailure() {
        return failure;
    }

    public boolean isFailed() {
        return failure != null;
    }

    @Override
    public String toString() {
        if (failure != null) {
            return "Strike(failed: " + failure + ")";
        }
        if (aim == null && button == null) {
            return "Strike(none)";
        }
        return "Strike(" + (aim == null ? "no aim" : "at " + aim)
                + (button == null ? "" : (held ? ", hold " : ", click ") + button)
                + (facing == null ? "" : ", when facing") + ")";
    }
}
