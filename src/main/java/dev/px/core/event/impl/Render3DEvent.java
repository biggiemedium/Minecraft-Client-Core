package dev.px.core.event.impl;

import dev.px.core.event.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Posted once per frame during world rendering, with the 3D backend ready to draw. */
@Getter
@AllArgsConstructor
public final class Render3DEvent extends Event {

    private final float partialTicks;
}
