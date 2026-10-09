package dev.px.core.flow;

/** Why a {@link Step} was stopped, as {@link Step#stop} hears it. */
public enum StopReason {

    /** It returned {@link Status#DONE}. */
    FINISHED,

    /** It returned {@link Status#FAILED}, or threw. */
    FAILED,

    /**
     * Something more urgent took over: an interrupt, a reflex, the player leaving
     * the world. It will be started again when that is over, with
     * {@link FlowContext#isResuming()} true.
     */
    PAUSED,

    /** It is no longer wanted: the flow was cancelled, a race was won by another branch, an {@code until} came true. */
    CANCELLED
}
