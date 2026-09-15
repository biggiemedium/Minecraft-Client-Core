package dev.px.core.event.bus;

import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import lombok.Getter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * One {@link Subscribe} method, resolved once per listener class and reused for
 * every instance of it.
 */
@Getter
final class HandlerMethod {

    private final Class<?> eventType;
    private final int priority;
    private final Stage stage;
    private final boolean receiveCancelled;
    private final boolean ignoreListening;
    private final Dispatcher dispatcher;
    private final String description;

    private HandlerMethod(Method method, Subscribe annotation, Dispatcher dispatcher) {
        this.eventType = method.getParameterTypes()[0];
        this.priority = annotation.priority();
        this.stage = annotation.stage();
        this.receiveCancelled = annotation.receiveCancelled();
        this.ignoreListening = annotation.ignoreListening();
        this.dispatcher = dispatcher;
        this.description = method.getDeclaringClass().getSimpleName() + '#' + method.getName();
    }

    /**
     * @throws IllegalArgumentException if the method is not a usable handler, so
     *         the mistake surfaces at registration rather than as silence at runtime
     */
    static HandlerMethod of(Method method, Subscribe annotation) {
        if (method.getParameterCount() != 1) {
            throw new IllegalArgumentException("@Subscribe method " + describe(method)
                    + " must take exactly one parameter (the event), but takes " + method.getParameterCount());
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("@Subscribe method " + describe(method) + " must not be static");
        }
        if (!method.isAccessible()) {
            method.setAccessible(true);
        }
        Dispatcher dispatcher = LambdaDispatcher.tryCreate(method);
        return new HandlerMethod(method, annotation, dispatcher != null ? dispatcher : new ReflectiveDispatcher(method));
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getName() + '#' + method.getName();
    }
}
