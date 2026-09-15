package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A printable character produced by the keyboard.
 *
 * <p>Separate from {@link KeyEvent} because text entry needs the character the
 * layout produced, not the physical key: the same key yields different
 * characters on different keyboard layouts.
 */
@Getter
@AllArgsConstructor
public final class CharTypedEvent extends CancellableEvent {

    private final char character;
}
