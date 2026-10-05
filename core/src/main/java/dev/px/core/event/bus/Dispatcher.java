package dev.px.core.event.bus;

/**
 * Invokes one handler method. Abstracted so the bus can use a fast
 * {@link LambdaDispatcher} where the JVM allows it and fall back to
 * {@link ReflectiveDispatcher} where it does not.
 */
public interface Dispatcher {

    void invoke(Object listener, Object event) throws Throwable;
}
