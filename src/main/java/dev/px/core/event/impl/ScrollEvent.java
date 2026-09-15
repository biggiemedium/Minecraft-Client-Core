package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Mouse wheel movement. Positive scrolls up. */
@Getter
@AllArgsConstructor
public final class ScrollEvent extends CancellableEvent {

    private final float amount;
    private final float x;
    private final float y;
}
