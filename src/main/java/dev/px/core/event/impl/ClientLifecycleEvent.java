package dev.px.core.event.impl;

import dev.px.core.event.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Core finished starting, or is about to shut down.
 *
 * <p>The hook for anything that needs every service and module present:
 * registering a HUD element that depends on another, or saving state on exit.
 */
@Getter
@AllArgsConstructor
public final class ClientLifecycleEvent extends Event {

    private final Phase phase;

    public enum Phase {
        /** All services started and modules registered. */
        STARTED,
        /** Shutdown has begun; services are still up. Last chance to persist state. */
        STOPPING
    }
}
