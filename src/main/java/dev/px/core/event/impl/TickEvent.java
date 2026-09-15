package dev.px.core.event.impl;

import dev.px.core.event.Stage;
import dev.px.core.event.StagedEvent;

/**
 * The game tick, posted before and after the game processes it.
 *
 * <p>Compare with the old PlayerMotionEvent at 70 lines for six fields: the
 * boilerplate is gone, but the shape is the same.
 */
public final class TickEvent extends StagedEvent {

    public TickEvent(Stage stage) {
        super(stage);
    }
}
