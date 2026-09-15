package dev.px.core.event.bus;

import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;

/** Universal fallback dispatcher: a plain reflective call on an accessible method. */
@RequiredArgsConstructor
final class ReflectiveDispatcher implements Dispatcher {

    private final Method method;

    @Override
    public void invoke(Object listener, Object event) throws Throwable {
        method.invoke(listener, event);
    }
}
