package dev.px.core.event;

/**
 * Marker base for everything posted on the {@link EventBus}.
 *
 * <p>Plain events carry no behaviour of their own. Extend {@link CancellableEvent}
 * when handlers must be able to suppress the underlying action, and
 * {@link StagedEvent} when the same call site fires before and after that action.
 */
public abstract class Event {

    /** The event's dispatch stage, or {@link Stage#ANY} when the event is not staged. */
    public Stage getStage() {
        return Stage.ANY;
    }
}
