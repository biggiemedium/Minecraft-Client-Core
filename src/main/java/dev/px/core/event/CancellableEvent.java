package dev.px.core.event;

import lombok.Getter;
import lombok.Setter;

/**
 * Base for events whose underlying action can be suppressed.
 *
 * <p>Once cancelled, the event stops reaching further handlers unless they opt
 * in with {@link Subscribe#receiveCancelled()}.
 */
@Getter
@Setter
public abstract class CancellableEvent extends Event implements Cancellable {

    private boolean cancelled;
}
