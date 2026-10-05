package dev.px.core.event.impl;

import dev.px.core.event.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The player joined or left a world.
 *
 * <p>The signal for anything that must not carry state across worlds: cached
 * targets, position history, per-server config.
 */
@Getter
@AllArgsConstructor
public final class WorldEvent extends Event {

    private final boolean loaded;

    /** Server address, or empty for singleplayer. */
    private final String serverAddress;

    public boolean isUnloaded() {
        return !loaded;
    }

    public boolean isSingleplayer() {
        return serverAddress == null || serverAddress.isEmpty();
    }
}
