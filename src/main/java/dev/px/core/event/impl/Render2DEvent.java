package dev.px.core.event.impl;

import dev.px.core.event.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Posted once per frame with the 2D backend already in a frame, so handlers can
 * draw immediately without any setup of their own.
 */
@Getter
@AllArgsConstructor
public final class Render2DEvent extends Event {

    /** Interpolation between the last two ticks, for smoothing animated positions. */
    private final float partialTicks;

    private final float screenWidth;
    private final float screenHeight;
}
