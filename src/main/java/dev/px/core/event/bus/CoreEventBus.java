package dev.px.core.event.bus;

import dev.px.core.event.Event;
import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Subscribe;
import dev.px.core.event.Subscription;
import dev.px.core.util.CoreLogger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The default {@link EventBus}.
 *
 * <p>Handler <em>discovery</em> is cached per listener class, so registering the
 * hundredth module of a type costs no reflection. Handler <em>lookup</em> is
 * cached per posted event class and honours supertypes, so a handler declared on
 * a base event also receives its subclasses. Both caches are invalidated on any
 * subscription change.
 *
 * <p>A handler that throws is logged and skipped rather than being allowed to
 * tear down the frame that posted the event: one broken module must not take the
 * client down with it.
 */
public final class CoreEventBus implements EventBus {

    /** Discovered handlers per listener class, shared across every instance of it. */
    private static final Map<Class<?>, List<HandlerMethod>> DISCOVERY_CACHE = new ConcurrentHashMap<>();

    private static final Comparator<BoundHandler> BY_PRIORITY =
            Comparator.comparingInt((BoundHandler handler) -> handler.priority).reversed();

    private static final BoundHandler[] NONE = new BoundHandler[0];

    private final Map<Object, List<BoundHandler>> byListener = new IdentityHashMap<>();
    private final List<BoundHandler> all = new ArrayList<>();
    private final Map<Class<?>, BoundHandler[]> lookupCache = new ConcurrentHashMap<>();
    private final CoreLogger logger;

    public CoreEventBus(CoreLogger logger) {
        this.logger = logger;
    }

    @Override
    public void subscribe(Object listener) {
        if (listener == null) {
            return;
        }
        List<HandlerMethod> methods = discover(listener.getClass());
        if (methods.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (byListener.containsKey(listener)) {
                return;
            }
            List<BoundHandler> bound = new ArrayList<>(methods.size());
            for (HandlerMethod method : methods) {
                bound.add(new BoundHandler(listener, method));
            }
            byListener.put(listener, bound);
            all.addAll(bound);
            all.sort(BY_PRIORITY);
            lookupCache.clear();
        }
    }

    @Override
    public void unsubscribe(Object listener) {
        if (listener == null) {
            return;
        }
        synchronized (this) {
            List<BoundHandler> bound = byListener.remove(listener);
            if (bound != null) {
                all.removeAll(bound);
                lookupCache.clear();
            }
        }
    }

    @Override
    public boolean isSubscribed(Object listener) {
        synchronized (this) {
            return byListener.containsKey(listener);
        }
    }

    @Override
    public <T extends Event> T post(T event) {
        if (event == null) {
            return null;
        }
        for (BoundHandler handler : resolve(event.getClass())) {
            if (!handler.accepts(event)) {
                continue;
            }
            try {
                handler.invoke(event);
            } catch (Throwable thrown) {
                logger.error("Handler " + handler.description() + " threw on "
                        + event.getClass().getSimpleName(), thrown);
            }
        }
        return event;
    }

    @Override
    public <T extends Event> Subscription on(Class<T> type, Consumer<? super T> handler) {
        return on(type, Priority.NORMAL, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> Subscription on(Class<T> type, int priority, Consumer<? super T> handler) {
        // A fresh key object means the same lambda can be registered more than once
        // and each registration closed independently.
        Object key = new Object();
        BoundHandler bound = new BoundHandler(key, type, priority, (Consumer<Object>) handler);
        synchronized (this) {
            byListener.put(key, new ArrayList<>(Collections.singletonList(bound)));
            all.add(bound);
            all.sort(BY_PRIORITY);
            lookupCache.clear();
        }
        return new LambdaSubscription(key);
    }

    @Override
    public void clear() {
        synchronized (this) {
            byListener.clear();
            all.clear();
            lookupCache.clear();
        }
    }

    private BoundHandler[] resolve(Class<?> eventType) {
        BoundHandler[] cached = lookupCache.get(eventType);
        if (cached != null) {
            return cached;
        }
        List<BoundHandler> matches = new ArrayList<>();
        synchronized (this) {
            for (BoundHandler handler : all) {
                if (handler.eventType.isAssignableFrom(eventType)) {
                    matches.add(handler);
                }
            }
        }
        BoundHandler[] resolved = matches.isEmpty() ? NONE : matches.toArray(new BoundHandler[0]);
        lookupCache.put(eventType, resolved);
        return resolved;
    }

    /** Walks the hierarchy so handlers inherited from a base module are found too. */
    private static List<HandlerMethod> discover(Class<?> type) {
        return DISCOVERY_CACHE.computeIfAbsent(type, root -> {
            List<HandlerMethod> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Class<?> current = root; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    Subscribe annotation = method.getAnnotation(Subscribe.class);
                    if (annotation == null) {
                        continue;
                    }
                    // An override and the method it overrides are one handler; keep the override.
                    String signature = method.getName() + Arrays.toString(method.getParameterTypes());
                    if (seen.add(signature)) {
                        found.add(HandlerMethod.of(method, annotation));
                    }
                }
            }
            return found;
        });
    }

    private final class LambdaSubscription implements Subscription {

        private final Object key;
        private boolean active = true;

        private LambdaSubscription(Object key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (active) {
                active = false;
                unsubscribe(key);
            }
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
