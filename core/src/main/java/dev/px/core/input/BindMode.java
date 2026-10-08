package dev.px.core.input;

/**
 * What a {@link Bind} does when its key goes down and comes up.
 *
 * <pre>{@code
 * private final BindSetting key = toggledBy(bind("Keybind", Bind.hold(Key.C)));
 * }</pre>
 *
 * <p>Part of the bind's value, so it is saved with it, reset with it, and kept
 * when the user rebinds the key.
 */
public enum BindMode {

    /** Acts on the press: a module's toggle bind flips it, an action runs. */
    PRESS,

    /**
     * Lasts as long as the key is down: a module's toggle bind switches it on
     * when pressed and off when released, and an action runs its press body
     * then its release body.
     */
    HOLD
}
