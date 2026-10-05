package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * The player is about to send a chat message.
 *
 * <p>The message is mutable, so a handler can rewrite it, and cancellable, so
 * the command system can consume a line without it reaching the server.
 */
@Getter
@Setter
@AllArgsConstructor
public final class ChatSendEvent extends CancellableEvent {

    private String message;
}
