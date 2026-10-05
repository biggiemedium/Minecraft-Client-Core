package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A game screen is opening or closing.
 *
 * <p>The screen is carried as an opaque {@link Object} because its type is
 * version specific; the adapter casts it. Core only needs to know that the
 * screen state changed, so a HUD can hide itself or a GUI can release focus.
 */
@Getter
@AllArgsConstructor
public final class ScreenEvent extends CancellableEvent {

    private final Object screen;
    private final boolean opening;

    public boolean isClosing() {
        return !opening;
    }

    /** @return the screen cast to a version-specific type, or null if it is something else. */
    public <T> T as(Class<T> type) {
        return type.isInstance(screen) ? type.cast(screen) : null;
    }
}
