package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/** A mouse button going down or coming up, in scaled screen coordinates. */
@Getter
@AllArgsConstructor
public final class MouseEvent extends CancellableEvent {

    private final MouseButton button;
    private final Set<Modifier> modifiers;
    private final boolean pressed;
    private final float x;
    private final float y;

    public boolean isReleased() {
        return !pressed;
    }
}
