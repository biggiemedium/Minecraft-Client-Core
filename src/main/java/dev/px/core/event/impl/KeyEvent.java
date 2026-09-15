package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/**
 * A key going down or coming up.
 *
 * <p>One event rather than a press and a release class, because almost every
 * handler cares about only one of the two and filtering on a boolean is cheaper
 * than two nearly identical types. Cancelling suppresses the key for the game.
 */
@Getter
@AllArgsConstructor
public final class KeyEvent extends CancellableEvent {

    private final Key key;
    private final Set<Modifier> modifiers;
    private final boolean pressed;

    public boolean isReleased() {
        return !pressed;
    }

    public boolean has(Modifier modifier) {
        return modifiers.contains(modifier);
    }
}
