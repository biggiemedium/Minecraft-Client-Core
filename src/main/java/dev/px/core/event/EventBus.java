package dev.px.core.event;

import java.util.function.Consumer;

/**
 * Dispatches events to {@link Subscribe}-annotated methods.
 *
 * <p>Implementations must be safe to post to from any thread and must tolerate
 * subscribe/unsubscribe calls made from inside a handler.
 */
public interface EventBus {

    /**
     * Registers every {@link Subscribe} method on {@code listener}, including
     * inherited ones. Registering the same instance twice is a no-op.
     */
    void subscribe(Object listener);

    /** Removes every handler belonging to {@code listener}. */
    void unsubscribe(Object listener);

    boolean isSubscribed(Object listener);

    /**
     * Posts an event to every matching handler, in descending priority order.
     *
     * @return the same event, so callers can post and inspect in one expression:
     *         {@code if (bus.post(new PlayerJumpEvent()).isCancelled()) return;}
     */
    <T extends Event> T post(T event);

    /**
     * Registers a standalone handler for one event type.
     *
     * <p>The returned {@link Subscription} is the only way to remove it, so hold
     * on to it for anything that outlives the caller.
     */
    <T extends Event> Subscription on(Class<T> type, Consumer<? super T> handler);

    <T extends Event> Subscription on(Class<T> type, int priority, Consumer<? super T> handler);

    /** Drops every handler. Intended for shutdown and for tests. */
    void clear();
}
