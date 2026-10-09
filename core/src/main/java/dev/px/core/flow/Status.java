package dev.px.core.flow;

/** What a {@link Step} says after each tick. */
public enum Status {

    /** Not finished: tick again next tick. */
    RUNNING,

    /** Finished, and it worked. */
    DONE,

    /** Finished, and it did not. Return it through {@link FlowContext#fail(String)} to say why. */
    FAILED
}
