package dev.px.core.event.bus;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/**
 * Dispatcher backed by a {@link LambdaMetafactory}-generated {@link BiConsumer}.
 *
 * <p>The generated call site is a direct invocation the JIT can inline, which
 * matters for packet events firing tens of times per tick. Not every method can
 * be linked this way, so {@link #tryCreate} returns {@code null} rather than
 * throwing and the bus falls back to reflection.
 */
final class LambdaDispatcher implements Dispatcher {

    private final BiConsumer<Object, Object> invoker;

    private LambdaDispatcher(BiConsumer<Object, Object> invoker) {
        this.invoker = invoker;
    }

    @SuppressWarnings("unchecked")
    static LambdaDispatcher tryCreate(Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle handle = lookup.unreflect(method);
            CallSite site = LambdaMetafactory.metafactory(
                    lookup,
                    "accept",
                    MethodType.methodType(BiConsumer.class),
                    MethodType.methodType(void.class, Object.class, Object.class),
                    handle,
                    handle.type());
            return new LambdaDispatcher((BiConsumer<Object, Object>) site.getTarget().invokeExact());
        } catch (Throwable ignored) {
            // Private handlers and cross-classloader lookups land here; reflection covers them.
            return null;
        }
    }

    @Override
    public void invoke(Object listener, Object event) {
        invoker.accept(listener, event);
    }
}
