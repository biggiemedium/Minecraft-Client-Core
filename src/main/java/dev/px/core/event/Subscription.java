package dev.px.core.event;

/**
 * Handle to a lambda handler registered via {@link EventBus#on}. Closing it
 * removes the handler; closing twice is a no-op.
 */
public interface Subscription extends AutoCloseable {

    @Override
    void close();

    boolean isActive();
}
