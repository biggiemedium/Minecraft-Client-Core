package dev.px.core.event;

/**
 * Implemented by events whose underlying action a handler may suppress.
 *
 * <p>Kept separate from {@link CancellableEvent} so adapters can make a
 * third-party event type cancellable without changing its supertype.
 */
public interface Cancellable {

    boolean isCancelled();

    void setCancelled(boolean cancelled);

    /** Cancels the event. Shorthand for {@code setCancelled(true)}. */
    default void cancel() {
        setCancelled(true);
    }
}
