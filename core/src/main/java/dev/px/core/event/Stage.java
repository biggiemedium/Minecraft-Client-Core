package dev.px.core.event;

/**
 * When, relative to the action it describes, an event was posted.
 *
 * <p>{@link #ANY} is only used as a subscription filter and by non-staged events;
 * it is never the stage of a posted {@link StagedEvent}.
 */
public enum Stage {

    /** Posted before the action runs. Mutations made here affect the action. */
    PRE,

    /** Posted after the action ran. Mutations here are observational. */
    POST,

    /** Subscription wildcard: receive the event at every stage. */
    ANY;

    /** @return whether an event at {@code actual} should reach a handler filtering on this stage. */
    public boolean accepts(Stage actual) {
        return this == ANY || this == actual;
    }
}
