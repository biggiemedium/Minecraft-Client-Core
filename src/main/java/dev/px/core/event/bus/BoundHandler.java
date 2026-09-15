package dev.px.core.event.bus;

import dev.px.core.event.Cancellable;
import dev.px.core.event.Event;
import dev.px.core.event.Listenable;
import dev.px.core.event.Stage;

import java.util.function.Consumer;

/**
 * A handler bound to the instance that owns it, plus the gating rules that decide
 * whether a given event actually reaches it.
 */
final class BoundHandler {

    final Object owner;
    final Class<?> eventType;
    final int priority;

    private final HandlerMethod method;
    private final Consumer<Object> lambda;
    private final Stage stage;
    private final boolean receiveCancelled;
    private final boolean ignoreListening;

    BoundHandler(Object owner, HandlerMethod method) {
        this.owner = owner;
        this.method = method;
        this.lambda = null;
        this.eventType = method.getEventType();
        this.priority = method.getPriority();
        this.stage = method.getStage();
        this.receiveCancelled = method.isReceiveCancelled();
        this.ignoreListening = method.isIgnoreListening();
    }

    BoundHandler(Object owner, Class<?> eventType, int priority, Consumer<Object> lambda) {
        this.owner = owner;
        this.method = null;
        this.lambda = lambda;
        this.eventType = eventType;
        this.priority = priority;
        this.stage = Stage.ANY;
        this.receiveCancelled = false;
        this.ignoreListening = true;
    }

    boolean accepts(Event event) {
        if (!eventType.isInstance(event)) {
            return false;
        }
        if (!stage.accepts(event.getStage())) {
            return false;
        }
        if (!receiveCancelled && event instanceof Cancellable && ((Cancellable) event).isCancelled()) {
            return false;
        }
        return ignoreListening || !(owner instanceof Listenable) || ((Listenable) owner).isListening();
    }

    void invoke(Event event) throws Throwable {
        if (lambda != null) {
            lambda.accept(event);
        } else {
            method.getDispatcher().invoke(owner, event);
        }
    }

    String description() {
        return method != null ? method.getDescription() : owner.getClass().getSimpleName() + ".lambda";
    }
}
