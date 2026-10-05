package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * A chat message arrived from the server.
 *
 * <p>Mutable so a handler can rewrite it, and cancellable so it can be hidden
 * entirely.
 */
@Getter
@Setter
@AllArgsConstructor
public final class ChatReceiveEvent extends CancellableEvent {

    private String message;

    /** The message with colour and formatting codes removed, for matching against. */
    private final String plainText;
}
